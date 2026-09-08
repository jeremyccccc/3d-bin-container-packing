package com.github.skjolber.packing.service.service;

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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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

	PackingVisualizationStore(ObjectMapper objectMapper, Path resultsDirectory) {
		this(objectMapper, resultsDirectory, true);
	}

	private PackingVisualizationStore(ObjectMapper objectMapper, Path resultsDirectory, boolean enabled) {
		this.objectMapper = objectMapper;
		this.resultsDirectory = resultsDirectory;
		this.enabled = enabled;
	}

	static PackingVisualizationStore disabled() {
		return new PackingVisualizationStore(new ObjectMapper(), Path.of("packing-results"), false);
	}

	String newResultId() {
		String timestamp = ID_TIMESTAMP.format(OffsetDateTime.now());
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return "pack_" + timestamp + "_" + suffix;
	}

	void save(String resultId, PackingRequest request, PackingResponse response, PackagerResult result) throws IOException {
		if (!enabled) {
			return;
		}
		Files.createDirectories(resultsDirectory);

		ObjectNode root = objectMapper.createObjectNode();
		root.put("resultId", resultId);
		root.put("masterBsId", request != null ? request.masterBsId() : null);
		root.put("createdAt", OffsetDateTime.now().toString());
		root.set("request", objectMapper.valueToTree(request));
		root.set("response", objectMapper.valueToTree(response));
		root.set("visualization", toVisualization(result.getContainers()));

		objectMapper.writerWithDefaultPrettyPrinter().writeValue(file(resultId).toFile(), root);
	}

	public Optional<JsonNode> findVisualization(String resultId) throws IOException {
		if (!isSafeResultId(resultId)) {
			return Optional.empty();
		}
		Path file = file(resultId);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		JsonNode root = objectMapper.readTree(file.toFile());
		JsonNode visualization = root.path("visualization");
		return visualization.isMissingNode() || visualization.isNull() ? Optional.empty() : Optional.of(visualization);
	}

	ObjectNode toVisualization(List<Container> containers) {
		ObjectNode root = objectMapper.createObjectNode();
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
				String cargoId = placement.getBox().getProperty(PackingMapper.PROP_CARGO_ID);
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				String inboundId = placement.getBox().getProperty(PackingMapper.PROP_INBOUND_ID);
				String customer = placement.getBox().getProperty(PackingMapper.PROP_CUSTOMER);
				int pieceIndex = pieceIndexes.merge(cargoId, 1, Integer::sum);

				ObjectNode placementNode = placementNodes.addObject();
				placementNode.put("step", step);
				placementNode.putArray("plugins");
				placementNode.put("x", placement.getAbsoluteX());
				placementNode.put("y", placement.getAbsoluteY());
				placementNode.put("z", placement.getAbsoluteZ());
				placementNode.put("houseBsId", houseBsId);
				placementNode.put("inboundId", inboundId);
				placementNode.put("customer", customer);
				placementNode.put("cargoId", cargoId);
				placementNode.put("pieceIndex", pieceIndex);
				placementNode.putArray("points");

				ObjectNode stackableNode = placementNode.putObject("stackable");
				stackableNode.put("step", step++);
				stackableNode.putArray("plugins");
				stackableNode.put("id", cargoId + "|" + pieceIndex);
				stackableNode.put("name", houseBsId + " " + inboundId);
				stackableNode.put("dx", placement.getStackValue().getDx());
				stackableNode.put("dy", placement.getStackValue().getDy());
				stackableNode.put("dz", placement.getStackValue().getDz());
				stackableNode.put("type", "box");
			}
		}
		return root;
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
