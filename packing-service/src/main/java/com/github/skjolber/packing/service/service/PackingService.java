package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.dto.AllocatedContainerDto;
import com.github.skjolber.packing.service.dto.AllocatedHouseBillDto;
import com.github.skjolber.packing.service.dto.AllocatedItemDto;
import com.github.skjolber.packing.service.dto.ContainerDto;
import com.github.skjolber.packing.service.dto.HouseBillItemDto;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;

@Service
public class PackingService {
	private static final Logger LOGGER = LoggerFactory.getLogger(PackingService.class);

	private final PackingMapper mapper;
	private final PackingEngine engine;
	private final PackingVisualizationStore visualizationStore;

	@Autowired
	public PackingService(PackingMapper mapper, PackingEngine engine, PackingVisualizationStore visualizationStore) {
		this.mapper = mapper;
		this.engine = engine;
		this.visualizationStore = visualizationStore;
	}

	public PackingService(PackingMapper mapper, PackingEngine engine) {
		this.mapper = mapper;
		this.engine = engine;
		this.visualizationStore = PackingVisualizationStore.disabled();
	}

	public PackingResponse pack(PackingRequest request) {
		return pack(request, "");
	}

	public PackingResponse pack(PackingRequest request, String viewerBaseUrl) {
		try {
			PackingPlan plan = mapper.toPlan(request);
			if (plan.containerItems().isEmpty()) {
				return response(request, false, "没有支持的柜型", plan.warnings(), null, null, emptyAllocations(plan.requestedContainers()));
			}
			if (plan.boxItems().isEmpty()) {
				return response(request, true, "装箱成功", plan.warnings(), null, null, emptyAllocations(plan.requestedContainers()));
			}

			PackingOutcome outcome = engine.packOutcome(plan);
			PackagerResult result = outcome.result();
			if (result == null || !result.isSuccess()) {
				return response(request, false, "装箱失败", plan.warnings(), null, null, emptyAllocations(plan.requestedContainers()));
			}

			String resultId = visualizationStore.newResultId();
			String viewerUrl = viewerUrl(viewerBaseUrl, resultId);
			PackingPlan resultPlan = outcome.plan();
			boolean success = outcome.targetAchieved();
			String message = success ? "装箱成功"
					: "装箱失败：可查看" + result.size() + "柜布局";
			PackingResponse response = response(request, success, message, resultPlan.warnings(), resultId,
					viewerUrl, toAllocations(resultPlan, result, outcome.fallbackUsed() && success));
			visualizationStore.save(resultId, request, response, result);
			return response;
		} catch (Exception e) {
			LOGGER.error("Packing failed unexpectedly for masterBsId={}",
					request != null ? request.masterBsId() : null, e);
			return new PackingResponse(
					request != null ? request.masterBsId() : null,
					false,
					e.getMessage() != null ? e.getMessage() : "装箱异常",
					List.of(),
					null,
					null,
					request != null ? emptyAllocations(request.containerLists()) : List.of());
		}
	}

	private static PackingResponse response(PackingRequest request, boolean success, String message,
			List<String> warnings, String resultId, String viewerUrl, List<AllocatedContainerDto> containers) {
		return new PackingResponse(
				request.masterBsId(),
				success,
				message,
				warnings,
				resultId,
				viewerUrl,
				containers);
	}

	private static String viewerUrl(String viewerBaseUrl, String resultId) {
		String path = "/packing-viewer/" + resultId;
		if (viewerBaseUrl == null || viewerBaseUrl.isBlank()) {
			return path;
		}
		return viewerBaseUrl.replaceAll("/+$", "") + path;
	}

	private static List<AllocatedContainerDto> toAllocations(PackingPlan plan, PackagerResult result,
			boolean actualContainersOnly) {
		Map<String, Map<String, Integer>> allocatedUnits = countAllocatedUnits(result);
		Map<String, CargoLine> linesById = new HashMap<>();
		for (CargoLine line : plan.cargoLines()) {
			linesById.put(line.cargoId(), line);
		}

		Map<String, Map<String, Integer>> allocatedNum = distributeOriginalNum(plan.cargoLines(), allocatedUnits);
		List<AllocatedContainerDto> containers = new ArrayList<>();
		for (ContainerDto requested : plan.requestedContainers()) {
			if (actualContainersOnly && !allocatedUnits.containsKey(requested.id())) continue;
			Map<String, Integer> cargoUnits = allocatedUnits.getOrDefault(requested.id(), Map.of());
			Map<String, Integer> cargoNums = allocatedNum.getOrDefault(requested.id(), Map.of());
			containers.add(new AllocatedContainerDto(
					requested.id(),
					requested.size(),
					requested.type(),
					toHouseBillAllocations(cargoUnits, cargoNums, linesById)));
		}
		return containers;
	}

	private static Map<String, Map<String, Integer>> countAllocatedUnits(PackagerResult result) {
		Map<String, Map<String, Integer>> units = new LinkedHashMap<>();
		for (Container container : result.getContainers()) {
			Map<String, Integer> containerUnits = units.computeIfAbsent(container.getId(), ignored -> new LinkedHashMap<>());
			for (Placement placement : container.getStack().getPlacements()) {
				String cargoId = placement.getBox().getProperty(PackingMapper.PROP_CARGO_ID);
				containerUnits.merge(cargoId, 1, Integer::sum);
			}
		}
		return units;
	}

	private static List<AllocatedHouseBillDto> toHouseBillAllocations(Map<String, Integer> cargoUnits, Map<String, Integer> cargoNums, Map<String, CargoLine> linesById) {
		Map<String, List<AllocatedItemDto>> byHouseBill = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : cargoUnits.entrySet()) {
			CargoLine line = linesById.get(entry.getKey());
			if (line == null) {
				continue;
			}
			int units = entry.getValue();
			int num = cargoNums.getOrDefault(line.cargoId(), 0);
			HouseBillItemDto item = line.item();
			double ratio = units / (double) line.calculatedQuantity();
			byHouseBill
					.computeIfAbsent(line.houseBsId(), ignored -> new ArrayList<>())
					.add(new AllocatedItemDto(
							item.inboundId(),
							num,
							round3(item.weight() * ratio),
							round3(item.meas() * ratio),
							item.size()));
		}

		List<AllocatedHouseBillDto> houseBills = new ArrayList<>();
		for (Map.Entry<String, List<AllocatedItemDto>> entry : byHouseBill.entrySet()) {
			houseBills.add(new AllocatedHouseBillDto(entry.getKey(), entry.getValue()));
		}
		return houseBills;
	}

	private static Map<String, Map<String, Integer>> distributeOriginalNum(List<CargoLine> lines, Map<String, Map<String, Integer>> allocatedUnits) {
		Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
		for (CargoLine line : lines) {
			List<Share> shares = new ArrayList<>();
			int floorTotal = 0;
			for (Map.Entry<String, Map<String, Integer>> containerEntry : allocatedUnits.entrySet()) {
				int units = containerEntry.getValue().getOrDefault(line.cargoId(), 0);
				if (units <= 0) {
					continue;
				}
				double raw = line.item().num() * units / (double) line.calculatedQuantity();
				int floor = (int) Math.floor(raw);
				floorTotal += floor;
				shares.add(new Share(containerEntry.getKey(), line.cargoId(), floor, raw - floor));
			}

			int remaining = line.item().num() - floorTotal;
			shares.sort(Comparator.comparingDouble(Share::remainder).reversed());
			for (int i = 0; i < shares.size() && remaining > 0; i++, remaining--) {
				shares.get(i).increment();
			}

			for (Share share : shares) {
				result
						.computeIfAbsent(share.containerId(), ignored -> new LinkedHashMap<>())
						.put(share.cargoId(), share.num());
			}
		}
		return result;
	}

	private static List<AllocatedContainerDto> emptyAllocations(List<ContainerDto> requestedContainers) {
		List<AllocatedContainerDto> containers = new ArrayList<>();
		if (requestedContainers == null) {
			return containers;
		}
		for (ContainerDto container : requestedContainers) {
			containers.add(new AllocatedContainerDto(container.id(), container.size(), container.type(), List.of()));
		}
		return containers;
	}

	private static double round3(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	private static class Share {

		private final String containerId;
		private final String cargoId;
		private int num;
		private final double remainder;

		Share(String containerId, String cargoId, int num, double remainder) {
			this.containerId = containerId;
			this.cargoId = cargoId;
			this.num = num;
			this.remainder = remainder;
		}

		String containerId() {
			return containerId;
		}

		String cargoId() {
			return cargoId;
		}

		int num() {
			return num;
		}

		double remainder() {
			return remainder;
		}

		void increment() {
			num++;
		}
	}
}
