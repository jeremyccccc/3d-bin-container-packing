package com.github.skjolber.packing.service.service.blockbeam;

import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Placement;

final class BlockBeamSupportValidator {

	private BlockBeamSupportValidator() {
	}

	static boolean isSupported(int x, int y, int z, BlockBeamSearchPackager.Block block,
			List<Placement> fixedPlacements, List<BlockBeamSearchPackager.BlockPlacement> placedBlocks,
			PlacementSupport.Policy policy) {
		if (z == 0) return true;
		BoxStackValue unit = block.orientation();
		for (int ix = 0; ix < block.nx(); ix++) {
			for (int iy = 0; iy < block.ny(); iy++) {
				int unitX = x + ix * unit.getDx();
				int unitY = y + iy * unit.getDy();
				if (!isFootprintSupported(unitX, unitY, z, unit.getDx(), unit.getDy(),
						fixedPlacements, placedBlocks, policy)) return false;
			}
		}
		return true;
	}

	private static boolean isFootprintSupported(int x, int y, int z, int dx, int dy,
			List<Placement> fixedPlacements, List<BlockBeamSearchPackager.BlockPlacement> placedBlocks,
			PlacementSupport.Policy policy) {
		int endX = x + dx;
		int endY = y + dy;
		List<SupportRectangle> supports = new ArrayList<>();
		for (Placement placement : fixedPlacements) {
			if (placement.getAbsoluteEndZ() + 1 != z) continue;
			addSupport(supports, x, y, endX, endY,
					placement.getAbsoluteX(), placement.getAbsoluteY(),
					placement.getAbsoluteEndX() + 1, placement.getAbsoluteEndY() + 1);
		}
		for (BlockBeamSearchPackager.BlockPlacement placement : placedBlocks) {
			if (placement.z() + placement.block().dz() != z) continue;
			addSupport(supports, x, y, endX, endY,
					placement.x(), placement.y(),
					placement.x() + placement.block().dx(), placement.y() + placement.block().dy());
		}
		long area = (long) dx * dy;
		long supportedArea = coveredArea(supports);
		if (supportedArea / (double) area + 1.0e-12 < policy.minimumAreaRatio()) return false;
		double centerX = x + dx / 2.0;
		double centerY = y + dy / 2.0;
		if (policy.requireCenterSupport()
				&& supports.stream().noneMatch(rectangle -> rectangle.contains(centerX, centerY))) return false;
		int supportMinX = supports.stream().mapToInt(SupportRectangle::minX).min().orElse(endX);
		int supportMaxX = supports.stream().mapToInt(SupportRectangle::maxX).max().orElse(x);
		int supportMinY = supports.stream().mapToInt(SupportRectangle::minY).min().orElse(endY);
		int supportMaxY = supports.stream().mapToInt(SupportRectangle::maxY).max().orElse(y);
		int allowedX = policy.maximumOverhang(dx);
		int allowedY = policy.maximumOverhang(dy);
		return supportMinX - x <= allowedX && endX - supportMaxX <= allowedX
				&& supportMinY - y <= allowedY && endY - supportMaxY <= allowedY;
	}

	private static void addSupport(List<SupportRectangle> supports,
			int x, int y, int endX, int endY,
			int supportX, int supportY, int supportEndX, int supportEndY) {
		int minX = Math.max(x, supportX);
		int minY = Math.max(y, supportY);
		int maxX = Math.min(endX, supportEndX);
		int maxY = Math.min(endY, supportEndY);
		if (minX < maxX && minY < maxY) supports.add(new SupportRectangle(minX, minY, maxX, maxY));
	}

	private static long coveredArea(List<SupportRectangle> rectangles) {
		if (rectangles.isEmpty()) return 0L;
		List<Integer> xCoordinates = rectangles.stream()
				.flatMap(rectangle -> List.of(rectangle.minX(), rectangle.maxX()).stream())
				.distinct().sorted().toList();
		long area = 0L;
		for (int i = 0; i < xCoordinates.size() - 1; i++) {
			int minX = xCoordinates.get(i);
			int maxX = xCoordinates.get(i + 1);
			List<SupportInterval> intervals = rectangles.stream()
					.filter(rectangle -> rectangle.minX() <= minX && rectangle.maxX() >= maxX)
					.map(rectangle -> new SupportInterval(rectangle.minY(), rectangle.maxY()))
					.sorted(Comparator.comparingInt(SupportInterval::min)
							.thenComparingInt(SupportInterval::max))
					.toList();
			area += (long) (maxX - minX) * coveredLength(intervals);
		}
		return area;
	}

	private static long coveredLength(List<SupportInterval> intervals) {
		if (intervals.isEmpty()) return 0L;
		long length = 0L;
		int min = intervals.get(0).min();
		int max = intervals.get(0).max();
		for (int i = 1; i < intervals.size(); i++) {
			SupportInterval interval = intervals.get(i);
			if (interval.min() > max) {
				length += max - min;
				min = interval.min();
				max = interval.max();
			} else {
				max = Math.max(max, interval.max());
			}
		}
		return length + max - min;
	}

	private record SupportRectangle(int minX, int minY, int maxX, int maxY) {
		boolean contains(double x, double y) {
			return x >= minX && x < maxX && y >= minY && y < maxY;
		}
	}

	private record SupportInterval(int min, int max) {
	}
}
