package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.packer.plain.PlainPlacement;

final class LayoutPlacement extends PlainPlacement {

	private static final long serialVersionUID = 1L;

	private final LayoutScoring.Score layoutScore;

	LayoutPlacement(BoxStackValue stackValue, Point point, long supportedArea, LayoutScoring.Score layoutScore) {
		super(stackValue, point, supportedArea);
		this.layoutScore = layoutScore;
	}

	LayoutScoring.Score getLayoutScore() {
		return layoutScore;
	}
}
