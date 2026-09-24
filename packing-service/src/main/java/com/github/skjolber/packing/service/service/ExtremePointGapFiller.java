package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.ep.points3d.DefaultPointCalculator3D;

/** Greedy multi-start extreme-point insertion for a small partial-packing tail. */
final class ExtremePointGapFiller {

	private final PlacementSupport.Policy supportPolicy;

	ExtremePointGapFiller(PlacementSupport.Policy supportPolicy) {
		this.supportPolicy = supportPolicy;
	}

	PackagerResult fill(BlockBeamSearchPackager.PartialSolution source, long deadlineMillis,
			String logContext) {
		if (source == null || source.remainingItems().isEmpty() || expired(deadlineMillis)) return null;
		List<Box> units = expand(source.remainingItems(), deadlineMillis);
		if (units == null || expired(deadlineMillis)) return null;
		List<Comparator<Box>> orders = List.of(
				Comparator.comparingLong(Box::getVolume).reversed(),
				Comparator.comparingInt(ExtremePointGapFiller::longestSide).reversed()
						.thenComparing(Comparator.comparingLong(Box::getVolume).reversed()),
				Comparator.comparingLong(Box::getVolume));
		for (int strategy = 0; strategy < orders.size(); strategy++) {
			if (expired(deadlineMillis)) return deadline(logContext, units.size());
			List<Box> ordered = new ArrayList<>(units);
			ordered.sort(orders.get(strategy));
			if (expired(deadlineMillis)) return deadline(logContext, units.size());
			PackagerResult result = fill(source.container(), ordered, strategy, deadlineMillis);
			if (result != null && result.isSuccess()
					&& PlacementSupport.validate(result, supportPolicy).valid()) {
				System.out.println("packing-service EXTREME-POINT-GAP" + logContext
						+ " success=true strategy=" + strategy + " units=" + units.size());
				return result;
			}
		}
		System.out.println("packing-service EXTREME-POINT-GAP" + logContext
				+ " success=false units=" + units.size());
		return null;
	}

	private PackagerResult fill(Container source, List<Box> units, int strategy, long deadlineMillis) {
		if (expired(deadlineMillis)) return null;
		List<Placement> placements = new ArrayList<>(source.getStack().getPlacements());
		DefaultPointCalculator3D calculator = new DefaultPointCalculator3D(
				false, placements.size() + units.size() + 1);
		calculator.clearToSize(source.getLoadDx(), source.getLoadDy(), source.getLoadDz());
		List<Placement> obstacles = placements.stream()
				.sorted(Comparator.comparingInt(Placement::getAbsoluteZ)
						.thenComparingInt(Placement::getAbsoluteY)
						.thenComparingInt(Placement::getAbsoluteX)).toList();
		for (Placement obstacle : obstacles) {
			if (expired(deadlineMillis) || !calculator.addObstacle(obstacle)) return null;
		}

		for (Box box : units) {
			if (expired(deadlineMillis)) return null;
			Candidate best = null;
			for (int pointIndex = 0; pointIndex < calculator.size(); pointIndex++) {
				if (expired(deadlineMillis)) return null;
				Point point = calculator.get(pointIndex);
				for (BoxStackValue orientation : box.getStackValues()) {
					if (!point.fits3D(orientation)) continue;
					Placement placement = new Placement(orientation, pointIndex,
							point.getMinX(), point.getMinY(), point.getMinZ());
					List<Placement> withCandidate = new ArrayList<>(placements.size() + 1);
					withCandidate.addAll(placements);
					withCandidate.add(placement);
					if (!PlacementSupport.validate(placement, withCandidate, supportPolicy).valid()) continue;
					double score = score(point, orientation, source, strategy);
					if (best == null || score < best.score()) {
						best = new Candidate(pointIndex, placement, score);
					}
				}
			}
			if (best == null) return null;
			calculator.add(best.pointIndex(), best.placement());
			placements.add(best.placement());
		}
		if (expired(deadlineMillis)) return null;

		Container result = source.clone();
		result.getStack().addAll(placements);
		return new PackagerResult(List.of(result), 0L, false);
	}

	private static double score(Point point, BoxStackValue value, Container container, int strategy) {
		double residual = (point.getVolume() - value.getVolume())
				/ (double) Math.max(1L, point.getVolume());
		double compact = (point.getMinZ() + value.getDz()) / (double) container.getLoadDz()
				+ (point.getMinX() + value.getDx()) / (double) container.getLoadDx();
		double wall = (point.getMinX() == 0 ? 1.0 : 0.0)
				+ (point.getMinY() == 0 ? 1.0 : 0.0);
		return switch (strategy) {
			case 0 -> point.getMinZ() * 10_000.0 + point.getMinX() * 10.0 + point.getMinY() + residual;
			case 1 -> residual * 100_000.0 + compact * 1_000.0 - wall * 100.0;
			default -> compact * 100_000.0 + residual * 1_000.0 - wall * 100.0;
		};
	}

	private static List<Box> expand(List<BoxItem> items, long deadlineMillis) {
		List<Box> boxes = new ArrayList<>();
		for (BoxItem item : items) {
			for (int count = 0; count < item.getCount(); count++) {
				if (expired(deadlineMillis)) return null;
				boxes.add(item.getBox());
			}
		}
		return boxes;
	}

	private static PackagerResult deadline(String logContext, int units) {
		System.out.println("packing-service EXTREME-POINT-GAP" + logContext
				+ " success=false reason=deadline units=" + units);
		return null;
	}

	private static boolean expired(long deadlineMillis) {
		return Thread.currentThread().isInterrupted()
				|| deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private static int longestSide(Box box) {
		BoxStackValue value = box.getStackValues()[0];
		return Math.max(value.getDx(), Math.max(value.getDy(), value.getDz()));
	}

	private record Candidate(int pointIndex, Placement placement, double score) {
	}
}
