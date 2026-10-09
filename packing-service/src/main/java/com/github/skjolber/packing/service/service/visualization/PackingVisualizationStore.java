package com.github.skjolber.packing.service.service.visualization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;
import com.github.skjolber.packing.service.service.mapping.PackingMapper;

@Service
public class PackingVisualizationStore {

	private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final String RESULT_ID_PATTERN = "[A-Za-z0-9._-]+";

	private final ObjectMapper objectMapper;
	private final Path resultsDirectory;
	private final boolean enabled;

	@Autowired
	public PackingVisualizationStore(ObjectMapper objectMapper,
			@Value("${packing.results.dir:packing-results}") String resultsDirectory) {
		this(objectMapper, Path.of(resultsDirectory), true);
	}

	public PackingVisualizationStore(ObjectMapper objectMapper, Path resultsDirectory) {
		this(objectMapper, resultsDirectory, true);
	}

	private PackingVisualizationStore(ObjectMapper objectMapper, Path resultsDirectory, boolean enabled) {
		this.objectMapper = objectMapper;
		this.resultsDirectory = resultsDirectory;
		this.enabled = enabled;
	}

	public static PackingVisualizationStore disabled() {
		return new PackingVisualizationStore(new ObjectMapper(), Path.of("packing-results"), false);
	}

	public String newResultId() {
		String timestamp = ID_TIMESTAMP.format(OffsetDateTime.now());
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return "pack_" + timestamp + "_" + suffix;
	}

	public void save(String resultId, PackingRequest request, PackingResponse response, PackagerResult result) throws IOException {
		if (!enabled) {
			return;
		}
		Files.createDirectories(resultsDirectory);

		ObjectNode root = objectMapper.createObjectNode();
		root.put("resultId", resultId);
		root.put("masterBsId", request != null ? request.masterBsId() : null);
		root.put("masterNo", request != null ? request.masterNo() : null);
		root.put("createdAt", OffsetDateTime.now().toString());
		root.set("request", objectMapper.valueToTree(request));
		root.set("response", objectMapper.valueToTree(response));
		root.set("visualization", toVisualization(result.getContainers(), request));

		objectMapper.writerWithDefaultPrettyPrinter().writeValue(file(resultId).toFile(), root);
	}

	public Optional<JsonNode> findVisualization(String resultId) throws IOException {
		if (!isSafeResultId(resultId)) {
			return Optional.empty();
		}
		Path resultFile = file(resultId);
		if (!Files.isRegularFile(resultFile)) {
			return Optional.empty();
		}
		JsonNode visualization = objectMapper.readTree(resultFile.toFile()).path("visualization");
		return visualization.isMissingNode() || visualization.isNull() ? Optional.empty() : Optional.of(visualization);
	}

	ObjectNode toVisualization(List<Container> containers) {
		return toVisualization(containers, null);
	}

	ObjectNode toVisualization(List<Container> containers, PackingRequest request) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("masterBsId", request != null ? request.masterBsId() : null);
		root.put("masterNo", request != null ? displayNo(request.masterNo(), request.masterBsId()) : null);
		ArrayNode containerNodes = root.putArray("containers");
		int step = 0;
		for (Container container : containers) {
			ObjectNode containerNode = containerNodes.addObject();
			containerNode.put("step", step++);
			containerNode.putArray("plugins");
			containerNode.put("id", container.getId());
			containerNode.put("name", container.getDescription());
			containerNode.put("dx", container.getDx());
			containerNode.put("dy", container.getDy());
			containerNode.put("dz", container.getDz());
			containerNode.put("loadDx", container.getLoadDx());
			containerNode.put("loadDy", container.getLoadDy());
			containerNode.put("loadDz", container.getLoadDz());

			ObjectNode stackNode = containerNode.putObject("stack");
			stackNode.put("step", step++);
			stackNode.putArray("plugins");
			ArrayNode placementNodes = stackNode.putArray("placements");

			Map<String, Integer> pieceIndexes = new HashMap<>();
			for (Placement placement : container.getStack().getPlacements()) {
				var box = placement.getBox();
				String cargoId = placement.getBox().getProperty(PackingMapper.PROP_CARGO_ID);
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				String houseBsNo = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_NO);
				String inboundId = placement.getBox().getProperty(PackingMapper.PROP_INBOUND_ID);
				String inboundNo = placement.getBox().getProperty(PackingMapper.PROP_INBOUND_NO);
				String customer = placement.getBox().getProperty(PackingMapper.PROP_CUSTOMER);
				Integer heightPosition = placement.getBox().getProperty(PackingMapper.PROP_HEIGHT_POSITION);
				Boolean noPress = placement.getBox().getProperty(PackingMapper.PROP_NO_PRESS);
				Boolean doorSide = placement.getBox().getProperty(PackingMapper.PROP_DOOR_SIDE);
				houseBsNo = displayNo(houseBsNo, houseBsId);
				inboundNo = displayNo(inboundNo, inboundId);
				int pieceIndex = pieceIndexes.merge(cargoId, 1, Integer::sum);
				var original = box.getStackValue(0);
				var placed = placement.getStackValue();

				ObjectNode placementNode = placementNodes.addObject();
				placementNode.put("step", step);
				placementNode.putArray("plugins");
				placementNode.put("x", placement.getAbsoluteX());
				placementNode.put("y", placement.getAbsoluteY());
				placementNode.put("z", placement.getAbsoluteZ());
				placementNode.put("houseBsId", houseBsId);
				placementNode.put("houseBsNo", houseBsNo);
				placementNode.put("inboundId", inboundId);
				placementNode.put("inboundNo", inboundNo);
				placementNode.put("customer", customer);
				placementNode.put("description", box.getDescription());
				placementNode.put("unitWeightKg", box.getWeight() / 1000.0);
				placementNode.put("unitVolumeM3", box.getVolume() / 1_000_000_000.0);
				placementNode.put("originalDx", original.getDx());
				placementNode.put("originalDy", original.getDy());
				placementNode.put("originalDz", original.getDz());
				placementNode.put("heightPosition", heightPosition != null ? heightPosition : 0);
				placementNode.put("noPress", Boolean.TRUE.equals(noPress));
				placementNode.put("doorSide", Boolean.TRUE.equals(doorSide));
				placementNode.put("cargoId", cargoId);
				placementNode.put("pieceIndex", pieceIndex);
				placementNode.putArray("points");

				ObjectNode stackableNode = placementNode.putObject("stackable");
				stackableNode.put("step", step++);
				stackableNode.putArray("plugins");
				stackableNode.put("id", cargoId + "|" + pieceIndex);
				stackableNode.put("name", houseBsNo + " " + inboundNo);
				stackableNode.put("dx", placed.getDx());
				stackableNode.put("dy", placed.getDy());
				stackableNode.put("dz", placed.getDz());
				stackableNode.put("type", "box");
			}
		}
		return root;
	}

	private static String displayNo(String number, String id) {
		return number != null && !number.isBlank() ? number : id;
	}

	private Path file(String resultId) {
		if (!isSafeResultId(resultId)) {
			throw new InvalidPathException(resultId, "Unsafe packing result id");
		}
		return resultsDirectory.resolve(resultId + ".json").normalize();
	}

	private static boolean isSafeResultId(String resultId) {
		return resultId != null && resultId.matches(RESULT_ID_PATTERN);
	}
}
