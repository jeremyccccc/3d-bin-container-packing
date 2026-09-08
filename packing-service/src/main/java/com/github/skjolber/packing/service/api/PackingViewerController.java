package com.github.skjolber.packing.service.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PackingViewerController {

	@GetMapping({"/packing-viewer", "/packing-viewer/", "/packing-viewer/{resultId}"})
	public String viewer() {
		return "forward:/packing-viewer-page/index.html";
	}
}
