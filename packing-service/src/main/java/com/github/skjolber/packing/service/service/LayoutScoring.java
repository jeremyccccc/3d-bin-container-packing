package com.github.skjolber.packing.service.service;

import java.util.List;
import java.util.Objects;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;

final class LayoutScoring {

	private LayoutScoring() {
	}

	static Score evaluate(Point point, BoxStackValue value, List<Placement> placements,
			Container container, int minimumRemainingFootprintEdge) {
		Placement candidate = new Placement(value, point);
		long sameOrientationContactArea = 0L;
		long rotatedContactArea = 0L;
		int wallContacts = wallContacts(candidate, container);

		Line xLine = new Line(candidate.getAbsoluteX(), candidate.getAbsoluteEndX(), value.getDx());
		Line yLine = new Line(candidate.getAbsoluteY(), candidate.getAbsoluteEndY(), value.getDy());

		for (Placement placed : placements) {
			if (!sameCargo(candidate.getBox(), placed.getBox())) {
				continue;
			}
			long contactArea = sideContactArea(candidate, placed);
			if (sameOrientation(candidate, placed)) {
				sameOrientationContactArea += contactArea;
			} else {
				rotatedContactArea += contactArea;
			}

			if (sameXRow(candidate, placed)) {
				xLine.include(placed.getAbsoluteX(), placed.getAbsoluteEndX(), placed.getStackValue().getDx());
			}
			if (sameYRow(candidate, placed)) {
				yLine.include(placed.getAbsoluteY(), placed.getAbsoluteEndY(), placed.getStackValue().getDy());
			}
		}

		LineScore xScore = xLine.score(container.getLoadDx(), minimumRemainingFootprintEdge);
		LineScore yScore = yLine.score(container.getLoadDy(), minimumRemainingFootprintEdge);
		long localNarrowRemainder = narrowRemainder(point.getMaxX() - candidate.getAbsoluteEndX(),
				minimumRemainingFootprintEdge)
				+ narrowRemainder(point.getMaxY() - candidate.getAbsoluteEndY(), minimumRemainingFootprintEdge);

		long candidateSideArea = Math.max((long) value.getDx() * value.getDz(),
				(long) value.getDy() * value.getDz());
		long sameOrientationContactPermille = candidateSideArea == 0L ? 0L
				: Math.min(4000L, sameOrientationContactArea * 1000L / candidateSideArea);
		long rotatedContactPermille = candidateSideArea == 0L ? 0L
				: Math.min(4000L, rotatedContactArea * 1000L / candidateSideArea);

		return new Score(xScore.complete() || yScore.complete(), sameOrientationContactPermille,
				rotatedContactPermille,
				Math.max(xScore.coveragePermille(), yScore.coveragePermille()), wallContacts,
				xScore.internalGap() + yScore.internalGap(),
				Math.max(localNarrowRemainder, xScore.narrowRemainder() + yScore.narrowRemainder()));
	}

	private static long narrowRemainder(long remainder, int minimumRemainingFootprintEdge) {
		if (remainder > 0L && remainder < minimumRemainingFootprintEdge) {
			return minimumRemainingFootprintEdge - remainder;
		}
		return 0L;
	}

	private static boolean sameCargo(Box first, Box second) {
		return Objects.equals(first.getId(), second.getId());
	}

	private static boolean sameOrientation(Placement first, Placement second) {
		return first.getStackValue().getDx() == second.getStackValue().getDx()
				&& first.getStackValue().getDy() == second.getStackValue().getDy()
				&& first.getStackValue().getDz() == second.getStackValue().getDz();
	}

	private static boolean sameXRow(Placement first, Placement second) {
		return first.getAbsoluteY() == second.getAbsoluteY()
				&& first.getAbsoluteZ() == second.getAbsoluteZ()
				&& sameOrientation(first, second);
	}

	private static boolean sameYRow(Placement first, Placement second) {
		return first.getAbsoluteX() == second.getAbsoluteX()
				&& first.getAbsoluteZ() == second.getAbsoluteZ()
				&& sameOrientation(first, second);
	}

	private static long sideContactArea(Placement first, Placement second) {
		long contact = 0L;
		int overlapZ = overlap(first.getAbsoluteZ(), first.getAbsoluteEndZ(),
				second.getAbsoluteZ(), second.getAbsoluteEndZ());
		if (overlapZ == 0) {
			return 0L;
		}
		if (first.getAbsoluteEndX() + 1 == second.getAbsoluteX()
				|| second.getAbsoluteEndX() + 1 == first.getAbsoluteX()) {
			contact += (long) overlap(first.getAbsoluteY(), first.getAbsoluteEndY(),
					second.getAbsoluteY(), second.getAbsoluteEndY()) * overlapZ;
		}
		if (first.getAbsoluteEndY() + 1 == second.getAbsoluteY()
				|| second.getAbsoluteEndY() + 1 == first.getAbsoluteY()) {
			contact += (long) overlap(first.getAbsoluteX(), first.getAbsoluteEndX(),
					second.getAbsoluteX(), second.getAbsoluteEndX()) * overlapZ;
		}
		return contact;
	}

	private static int overlap(int firstStart, int firstEnd, int secondStart, int secondEnd) {
		return Math.max(0, Math.min(firstEnd, secondEnd) - Math.max(firstStart, secondStart) + 1);
	}

	private static int wallContacts(Placement placement, Container container) {
		int contacts = 0;
		if (placement.getAbsoluteX() == 0 || placement.getAbsoluteEndX() == container.getLoadDx() - 1) {
			contacts++;
		}
		if (placement.getAbsoluteY() == 0 || placement.getAbsoluteEndY() == container.getLoadDy() - 1) {
			contacts++;
		}
		return contacts;
	}

	static record Score(boolean completeRow, long sameOrientationContactPermille,
			long rotatedContactPermille, long rowCoveragePermille, int wallContacts,
			long internalGap, long narrowRemainder) implements Comparable<Score> {

		@Override
		public int compareTo(Score other) {
			int result = Boolean.compare(completeRow, other.completeRow);
			if (result != 0) return result;
			result = Long.compare(sameOrientationContactPermille, other.sameOrientationContactPermille);
			if (result != 0) return result;
			result = Long.compare(other.rotatedContactPermille, rotatedContactPermille);
			if (result != 0) return result;
			result = Integer.compare(wallContacts, other.wallContacts);
			if (result != 0) return result;
			result = Long.compare(other.internalGap, internalGap);
			if (result != 0) return result;
			result = Long.compare(other.narrowRemainder, narrowRemainder);
			if (result != 0) return result;
			return Long.compare(rowCoveragePermille, other.rowCoveragePermille);
		}
	}

	private static final class Line {
		private int min;
		private int max;
		private long occupied;

		private Line(int min, int max, int occupied) {
			this.min = min;
			this.max = max;
			this.occupied = occupied;
		}

		private void include(int itemMin, int itemMax, int length) {
			min = Math.min(min, itemMin);
			max = Math.max(max, itemMax);
			occupied += length;
		}

		private LineScore score(int containerLength, int minimumRemainingFootprintEdge) {
			long span = max - min + 1L;
			long internalGap = Math.max(0L, span - occupied);
			boolean complete = min == 0 && max == containerLength - 1 && internalGap == 0L;
			long coverage = Math.min(1000L, occupied * 1000L / containerLength);
			long narrowRemainder = 0L;
			if (internalGap == 0L && (min == 0 || max == containerLength - 1)) {
				long remainder = containerLength - occupied;
				if (remainder > 0L && remainder < minimumRemainingFootprintEdge) {
					narrowRemainder = minimumRemainingFootprintEdge - remainder;
				}
			}
			return new LineScore(complete, coverage, internalGap, narrowRemainder);
		}
	}

	private static record LineScore(boolean complete, long coveragePermille, long internalGap,
			long narrowRemainder) {
	}
}
