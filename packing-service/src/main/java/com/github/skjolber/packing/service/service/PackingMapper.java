package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.service.dto.ContainerDto;
import com.github.skjolber.packing.service.dto.HouseBillDto;
import com.github.skjolber.packing.service.dto.HouseBillItemDto;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingRuleDto;
import com.github.skjolber.packing.service.dto.SizeDto;

@Component
class PackingMapper {

	static final String PROP_CARGO_ID = "cargoId";
	static final String PROP_HOUSE_BS_ID = "houseBsId";
	static final String PROP_INBOUND_ID = "inboundId";
	static final String PROP_CUSTOMER = "customer";
	static final String PROP_HEIGHT_POSITION = "heightPosition";
	static final String PROP_NO_PRESS = "noPress";
	static final String PROP_DOOR_SIDE = "doorSide";

	private static final int DIMENSION_SCALE = 10;
	private static final int WEIGHT_SCALE = 1000;
	private static final double QUANTITY_INTEGER_TOLERANCE = 1e-9;
	private static final Map<String, ContainerSpec> CONTAINER_SPECS = Map.of(
			"20GP", new ContainerSpec(589.8, 235.2, 239.3, 28220),
			"40GP", new ContainerSpec(1203.2, 235.2, 239.3, 26780),
			"40HQ", new ContainerSpec(1203.2, 235.2, 269.8, 26600),
			"45HQ", new ContainerSpec(1356.0, 235.2, 269.8, 27600));

	PackingPlan toPlan(PackingRequest request) {
		List<String> warnings = new ArrayList<>();
		List<ContainerItem> containers = toContainerItems(request.containerLists(), warnings);
		List<CargoLine> cargoLines = toCargoLines(request.houseBillList(), warnings);
		List<BoxItem> boxItems = toBoxItems(cargoLines);

		return new PackingPlan(
				nullToEmpty(request.containerLists()),
				containers,
				boxItems,
				cargoLines,
				warnings);
	}

	private static List<ContainerItem> toContainerItems(List<ContainerDto> input, List<String> warnings) {
		List<ContainerItem> items = new ArrayList<>();
		for (ContainerDto container : nullToEmpty(input)) {
			ContainerItem mapped = toContainerItem(container);
			if (mapped == null) {
				warnings.add("UNSUPPORTED_CONTAINER_SKIPPED id=" + container.id() + " size=" + container.size() + " type=" + container.type());
				continue;
			}
			items.add(mapped);
		}
		return items;
	}

	static ContainerItem toContainerItem(ContainerDto container) {
		ContainerSpec spec = spec(container);
		if (spec == null) return null;
		Container mapped = Container
				.newBuilder()
				.withId(container.id())
				.withDescription(container.size() + container.type().toUpperCase(Locale.ROOT))
				.withSize(scaleCm(spec.lengthCm()), scaleCm(spec.widthCm()), scaleCm(spec.heightCm()))
				.withEmptyWeight(0)
				.withMaxLoadWeight(spec.maxLoadKg() * WEIGHT_SCALE)
				.build();
		return new ContainerItem(mapped, 1);
	}

	private static ContainerSpec spec(ContainerDto container) {
		if (container == null || container.type() == null) {
			return null;
		}
		return CONTAINER_SPECS.get(container.size() + container.type().toUpperCase(Locale.ROOT));
	}

	private static List<CargoLine> toCargoLines(List<HouseBillDto> houseBills, List<String> warnings) {
		List<CargoLine> lines = new ArrayList<>();
		for (HouseBillDto houseBill : nullToEmpty(houseBills)) {
			PackingRuleDto rule = validateRule(houseBill);
			int itemIndex = 0;
			for (HouseBillItemDto item : nullToEmpty(houseBill.items())) {
				itemIndex++;
				SizeDto size = item.size();
				int calculatedQuantity = calculateQuantity(item, warnings, houseBill.houseBsId());
				String cargoId = houseBill.houseBsId() + "|" + item.inboundId() + "|" + itemIndex;
				int unitWeight = (int) Math.ceil(item.weight() * WEIGHT_SCALE / calculatedQuantity);
				lines.add(new CargoLine(
						cargoId,
						houseBill.houseBsId(),
						houseBill.desc(),
						houseBill.customer(),
						rule.heightPosition(),
						isNoPress(rule),
						rule.doorSide(),
						item,
						calculatedQuantity,
						scaleCm(size.length()),
						scaleCm(size.width()),
						scaleCm(size.height()),
						Math.max(unitWeight, 0)));
			}
		}
		return lines;
	}

	private static List<BoxItem> toBoxItems(List<CargoLine> cargoLines) {
		List<BoxItem> items = new ArrayList<>();
		for (CargoLine line : cargoLines) {
			Box box = Box
					.newBuilder()
					.withId(line.cargoId())
					.withDescription(line.desc())
					.withSize(line.scaledLength(), line.scaledWidth(), line.scaledHeight())
					.withWeight(line.scaledUnitWeight())
					.withRotate3D()
					.withProperty(PROP_CARGO_ID, line.cargoId())
					.withProperty(PROP_HOUSE_BS_ID, line.houseBsId())
					.withProperty(PROP_INBOUND_ID, line.item().inboundId())
					.withProperty(PROP_CUSTOMER, line.customer())
					.withProperty(PROP_HEIGHT_POSITION, line.heightPosition())
					.withProperty(PROP_NO_PRESS, line.noPress())
					.withProperty(PROP_DOOR_SIDE, line.doorSide())
					.build();
			items.add(new BoxItem(box, line.calculatedQuantity()));
		}
		return items;
	}

	private static PackingRuleDto validateRule(HouseBillDto houseBill) {
		PackingRuleDto rule = houseBill.rule() != null ? houseBill.rule() : PackingRuleDto.none();
		String houseBsId = houseBill.houseBsId();
		if (rule.heightPosition() < 0 || rule.heightPosition() > 2) {
			throw new IllegalArgumentException("装箱规则高度位置无效，houseBsId=" + houseBsId + "，值=" + rule.heightPosition());
		}
		if (rule.method() < 0 || rule.method() > 2) {
			throw new IllegalArgumentException("装箱规则方法无效，houseBsId=" + houseBsId + "，值=" + rule.method());
		}
		if (rule.method() == 2) {
			throw new IllegalArgumentException("暂不支持平铺规则，Method=2，houseBsId=" + houseBsId);
		}
		if (rule.method() != 0 && rule.method() != 1) {
			throw new IllegalArgumentException("暂不支持该装箱规则，Method=" + rule.method() + "，houseBsId=" + houseBsId);
		}
		return rule;
	}

	private static boolean isNoPress(PackingRuleDto rule) {
		return rule.heightPosition() == 2 || rule.method() == 1;
	}

	private static int calculateQuantity(HouseBillItemDto item, List<String> warnings, String houseBsId) {
		SizeDto size = item.size();
		if (size == null || size.length() <= 0 || size.width() <= 0 || size.height() <= 0) {
			throw new IllegalArgumentException("货物尺寸无效，inboundId=" + item.inboundId());
		}
		double unitMeas = size.length() * size.width() * size.height() / 1_000_000.0;
		if (unitMeas <= 0 || item.meas() <= 0) {
			return Math.max(item.num(), 1);
		}
		double rawQuantity = item.meas() / unitMeas;
		double nearestInteger = Math.rint(rawQuantity);
		boolean isEffectivelyInteger = Math.abs(rawQuantity - nearestInteger) <= QUANTITY_INTEGER_TOLERANCE;
		int quantity = Math.max((int) Math.round(rawQuantity), 1);
		if (!isEffectivelyInteger) {
			warnings.add("MEAS_QUANTITY_ROUNDED houseBsId=" + houseBsId + " inboundId=" + item.inboundId()
					+ " calculated=" + rawQuantity + " rounded=" + quantity);
		}
		return quantity;
	}

	static int scaleCm(double value) {
		return (int) Math.round(value * DIMENSION_SCALE);
	}

	private static <T> List<T> nullToEmpty(List<T> values) {
		return values == null ? List.of() : values;
	}

	private record ContainerSpec(double lengthCm, double widthCm, double heightCm, int maxLoadKg) {
	}
}
