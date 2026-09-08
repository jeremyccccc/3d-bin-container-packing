package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;

import com.github.skjolber.packing.service.api.PackingController;

class PackingControllerTest {

	@Test
	void visualizationResultIdPathVariableHasExplicitName() throws Exception {
		Method method = PackingController.class.getMethod("visualization", String.class);
		PathVariable pathVariable = method.getParameters()[0].getAnnotation(PathVariable.class);

		assertThat(pathVariable).isNotNull();
		assertThat(pathVariable.value()).isEqualTo("resultId");
	}
}
