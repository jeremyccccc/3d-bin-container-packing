package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.service.dto.ContainerDto;
import com.github.skjolber.packing.service.dto.HouseBillDto;
import com.github.skjolber.packing.service.dto.HouseBillItemDto;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.SizeDto;

@Component
class PackingMapper {

	static final String PROP_CARGO_ID = "cargoId";
	static final String PROP_HOUSE_BS_ID = "houseBsId";
	static final String PROP_INBOUND_ID = "inboundId";

	private static final int DIMENSION_SCALE = 10;
	private static final int WEIGHT_SCALE = 1000;

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
			if (!isSupported40Hq(container)) {
				warnings.add("UNSUPPORTED_CONTAINER_SKIPPED id=" + container.id() + " size=" + container.size() + " type=" + container.type());
				continue;
			}

			Container fortyHq = Container
					.newBuilder()
					.withId(container.id())
					.withDescription(container.size() + container.type().toUpperCase(Locale.ROOT))
					.withSize(scaleCm(1203.2), scaleCm(235.2), scaleCm(269.8))
					.withEmptyWeight(0)
					.withMaxLoadWeight(26600 * WEIGHT_SCALE)
					.build();

			items.add(new ContainerItem(fortyHq, 1));
		}
		return items;
	}

	private static boolean isSupported40Hq(ContainerDto container) {
		return container != null && container.size() == 40 && container.type() != null && "HQ".equalsIgnoreCase(container.type());
	}

	private static List<CargoLine> toCargoLines(List<HouseBillDto> houseBills, List<String> warnings) {
		List<CargoLine> lines = new ArrayList<>();
		for (HouseBillDto houseBill : nullToEmpty(houseBills)) {
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
					.build();
			items.add(new BoxItem(box, line.calculatedQuantity()));
		}
		return items;
	}

	private static int calculateQuantity(HouseBillItemDto item, List<String> warnings, String houseBsId) {
		SizeDto size = item.size();
		if (size == null || size.length() <= 0 || size.width() <= 0 || size.height() <= 0) {
			throw new IllegalArgumentException("Invalid item size for inboundId=" + item.inboundId());
		}
		double unitMeas = size.length() * size.width() * size.height() / 1_000_000.0;
		if (unitMeas <= 0 || item.meas() <= 0) {
			return Math.max(item.num(), 1);
		}
		double rawQuantity = item.meas() / unitMeas;
		int quantity = Math.max((int) Math.ceil(rawQuantity), 1);
		if (Math.abs(rawQuantity - Math.rint(rawQuantity)) > 1e-9) {
			warnings.add("MEAS_QUANTITY_ROUNDED_UP houseBsId=" + houseBsId + " inboundId=" + item.inboundId()
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
}
