package com.github.skjolber.packing.service.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.PackingRequest;

@Service
public class PackingRequestLogStore {

	private static final DateTimeFormatter FILE_TIME =
			DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

	private final ObjectMapper objectMapper;
	private final Path requestLogDirectory;

	public PackingRequestLogStore(ObjectMapper objectMapper,
			@Value("${packing.request-log-dir:packing-request-logs}") String configuredDirectory) {
		this.objectMapper = objectMapper;
		this.requestLogDirectory = Path.of(
				configuredDirectory == null || configuredDirectory.isBlank()
						? "packing-request-logs"
						: configuredDirectory);
	}

	public void save(PackingRequest request) throws IOException {
		Files.createDirectories(requestLogDirectory);

		String fileName = "request_" + FILE_TIME.format(LocalDateTime.now()) + "_"
				+ UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".json";
		Path file = requestLogDirectory.resolve(fileName);
		String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);

		Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW);
	}

}
