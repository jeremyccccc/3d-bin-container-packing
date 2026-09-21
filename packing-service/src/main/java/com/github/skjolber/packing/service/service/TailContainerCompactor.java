package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/** Moves complete house bills out of the least-filled container without moving existing front-container cargo. */
final class TailContainerCompactor {

	private final PlacementSupport.Policy supportPolicy;
	private final int blockBeamWidth;
	private final int blockBranching;

	TailContainerCompactor(PlacementSupport.Policy supportPolicy, int blockBeamWidth, int blockBranching) {
		this.supportPolicy = supportPolicy;
		this.blockBeamWidth = blockBeamWidth;
		this.blockBranching = blockBranching;
	}

	PackagerResult compact(PackagerResult source, long deadlineMillis) {
		if (source == null || !source.isSuccess() || source.size() < 2) return source;
		long started = System.nanoTime();
		List<Container> containers = new ArrayList<>(source.getContainers());
		int tailIndex = leastFilledContainer(containers);
		Container originalTail = containers.get(tailIndex);
		long originalTailVolume = loadedVolume(originalTail);
		List<HouseBillLoad> houseBills = new ArrayList<>(houseBills(originalTail));
		houseBills.sort(Comparator.comparingLong(HouseBillLoad::volume).reversed()
				.thenComparing(HouseBillLoad::houseBsId));

		int attempts = 0;
		int movedBills = 0;
		long movedVolume = 0L;
		for (HouseBillLoad houseBill : houseBills) {
			if (expired(deadlineMillis)) break;
			if (!canPack(houseBill.items())) continue;
			List<Integer> targets = targetOrder(containers, tailIndex);
			for (int targetIndex : targets) {
				if (expired(deadlineMillis)) break;
				attempts++;
				Container target = containers.get(targetIndex);
				BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
						blockBeamWidth, blockBranching, supportPolicy);
				PackagerResult packed = packager.pack(target, houseBill.items(),
						target.getStack().getPlacements(), deadlineMillis,
						" phase=tail-compaction houseBsId=" + houseBill.houseBsId()
								+ " target=" + target.getId());
				if (packed == null || !packed.isSuccess()) continue;

				Container nextTail = prepareTailAfterRemoval(
						containers.get(tailIndex), houseBill.houseBsId(), deadlineMillis);
				Container nextTarget = packed.get(0);
				if (nextTail == null || !supported(nextTarget)) continue;

				containers.set(targetIndex, nextTarget);
				containers.set(tailIndex, nextTail);
				movedBills++;
				movedVolume += houseBill.volume();
				System.out.println("packing-service tail-compaction move houseBsId=" + houseBill.houseBsId()
						+ " target=" + nextTarget.getId() + " units=" + houseBill.units()
						+ " volume=" + houseBill.volume());
				break;
			}
		}

		if (movedBills == 0) return source;
		if (containers.get(tailIndex).getStack().getPlacements().isEmpty()) containers.remove(tailIndex);
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		if (!validFinalResult(source.getContainers(), containers)) {
			System.out.println("packing-service tail-compaction success=false reason=final-validation");
			return source;
		}
		System.out.println("packing-service tail-compaction success=true movedBills=" + movedBills
				+ " movedVolume=" + movedVolume + " originalTailVolume=" + originalTailVolume
				+ " remainingTailVolume=" + Math.max(0L, originalTailVolume - movedVolume)
				+ " attempts=" + attempts + " elapsedMs=" + elapsedMillis);
		return new PackagerResult(List.copyOf(containers), source.getDuration() + elapsedMillis, false);
	}

	private boolean supported(Container container) {
		List<Placement> placements = container.getStack().getPlacements();
		for (Placement placement : placements) {
			if (!PlacementSupport.validate(placement, placements, supportPolicy).valid()) return false;
		}
		return true;
	}

	private Container prepareTailAfterRemoval(Container source, String houseBsId, long deadlineMillis) {
		Container remaining = withoutHouseBill(source, houseBsId);
		if (remaining.getStack().getPlacements().isEmpty() || supported(remaining)) return remaining;
		if (!canRepack(remaining.getStack().getPlacements()) || expired(deadlineMillis)) return null;
		List<BoxItem> items = boxItems(remaining.getStack().getPlacements());
		BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
				blockBeamWidth, blockBranching, supportPolicy);
		PackagerResult repacked = packager.pack(remaining, items, deadlineMillis,
				" phase=tail-repack removedHouseBsId=" + houseBsId);
		if (repacked == null || !repacked.isSuccess() || !supported(repacked.get(0))) return null;
		return repacked.get(0);
	}

	private static boolean canRepack(List<Placement> placements) {
		for (Placement placement : placements) {
			if (!hasSimpleRules(placement.getBox())) return false;
		}
		return true;
	}

	private static boolean canPack(List<BoxItem> items) {
		for (BoxItem item : items) if (!hasSimpleRules(item.getBox())) return false;
		return true;
	}

	private static boolean hasSimpleRules(Box box) {
		Object noPress = box.getProperty(PackingMapper.PROP_NO_PRESS);
		Object doorSide = box.getProperty(PackingMapper.PROP_DOOR_SIDE);
		Object heightPosition = box.getProperty(PackingMapper.PROP_HEIGHT_POSITION);
		return !Boolean.TRUE.equals(noPress) && !Boolean.TRUE.equals(doorSide)
				&& (!(heightPosition instanceof Number number) || number.intValue() == 0);
	}

	private boolean validFinalResult(List<Container> source, List<Container> compacted) {
		if (!boxCounts(source).equals(boxCounts(compacted))) return false;
		Map<String, Integer> houseBillContainers = new LinkedHashMap<>();
		for (int containerIndex = 0; containerIndex < compacted.size(); containerIndex++) {
			Container container = compacted.get(containerIndex);
			List<Placement> placements = container.getStack().getPlacements();
			for (int i = 0; i < placements.size(); i++) {
				Placement placement = placements.get(i);
				if (placement.getAbsoluteX() < 0 || placement.getAbsoluteY() < 0 || placement.getAbsoluteZ() < 0
						|| placement.getAbsoluteEndX() >= container.getLoadDx()
						|| placement.getAbsoluteEndY() >= container.getLoadDy()
						|| placement.getAbsoluteEndZ() >= container.getLoadDz()) return false;
				for (int j = 0; j < i; j++) if (intersects(placement, placements.get(j))) return false;
				if (!PlacementSupport.validate(placement, placements, supportPolicy).valid()) return false;
				String houseBsId = String.valueOf((Object) placement.getBox()
						.getProperty(PackingMapper.PROP_HOUSE_BS_ID));
				Integer previous = houseBillContainers.putIfAbsent(houseBsId, containerIndex);
				if (previous != null && previous != containerIndex) return false;
			}
		}
		return true;
	}

	private static Map<String, Integer> boxCounts(List<Container> containers) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Container container : containers) {
			for (Placement placement : container.getStack().getPlacements()) {
				counts.merge(placement.getBox().getId(), 1, Integer::sum);
			}
		}
		return counts;
	}

	private static boolean intersects(Placement a, Placement b) {
		return a.getAbsoluteX() <= b.getAbsoluteEndX() && b.getAbsoluteX() <= a.getAbsoluteEndX()
				&& a.getAbsoluteY() <= b.getAbsoluteEndY() && b.getAbsoluteY() <= a.getAbsoluteEndY()
				&& a.getAbsoluteZ() <= b.getAbsoluteEndZ() && b.getAbsoluteZ() <= a.getAbsoluteEndZ();
	}

	private static int leastFilledContainer(List<Container> containers) {
		int index = 0;
		double fill = Double.MAX_VALUE;
		for (int i = 0; i < containers.size(); i++) {
			Container container = containers.get(i);
			double candidate = loadedVolume(container) / (double) container.getMaxLoadVolume();
			if (candidate < fill) {
				fill = candidate;
				index = i;
			}
		}
		return index;
	}

	private static List<Integer> targetOrder(List<Container> containers, int tailIndex) {
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < containers.size(); i++) if (i != tailIndex) indexes.add(i);
		indexes.sort(Comparator.<Integer>comparingDouble(index ->
				loadedVolume(containers.get(index)) / (double) containers.get(index).getMaxLoadVolume()).reversed()
				.thenComparingInt(Integer::intValue));
		return indexes;
	}

	private static List<HouseBillLoad> houseBills(Container tail) {
		Map<String, BillBuilder> bills = new LinkedHashMap<>();
		for (Placement placement : tail.getStack().getPlacements()) {
			Object property = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			String houseBsId = String.valueOf(property);
			bills.computeIfAbsent(houseBsId, BillBuilder::new).add(placement);
		}
		return bills.values().stream().map(BillBuilder::build).toList();
	}

	private static List<BoxItem> boxItems(List<Placement> placements) {
		Map<String, BoxCount> boxes = new LinkedHashMap<>();
		for (Placement placement : placements) {
			Box box = placement.getBox();
			boxes.computeIfAbsent(box.getId(), ignored -> new BoxCount(box)).count++;
		}
		return boxes.values().stream().map(value -> new BoxItem(value.box, value.count)).toList();
	}

	private static Container withoutHouseBill(Container source, String houseBsId) {
		Container copy = source.clone();
		for (Placement placement : source.getStack().getPlacements()) {
			Object property = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (!houseBsId.equals(String.valueOf(property))) copy.getStack().add(placement);
		}
		return copy;
	}

	private static long loadedVolume(Container container) {
		long volume = 0L;
		for (Placement placement : container.getStack().getPlacements()) {
			volume += placement.getStackValue().getVolume();
		}
		return volume;
	}

	private static boolean expired(long deadlineMillis) {
		return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private record HouseBillLoad(String houseBsId, List<BoxItem> items, int units, long volume) {
	}

	private static final class BillBuilder {
		private final String houseBsId;
		private final Map<String, BoxCount> boxes = new LinkedHashMap<>();
		private int units;
		private long volume;

		private BillBuilder(String houseBsId) {
			this.houseBsId = houseBsId;
		}

		private void add(Placement placement) {
			Box box = placement.getBox();
			boxes.computeIfAbsent(box.getId(), ignored -> new BoxCount(box)).count++;
			units++;
			volume += placement.getStackValue().getVolume();
		}

		private HouseBillLoad build() {
			List<BoxItem> items = boxes.values().stream()
					.map(value -> new BoxItem(value.box, value.count)).toList();
			return new HouseBillLoad(houseBsId, items, units, volume);
		}
	}

	private static final class BoxCount {
		private final Box box;
		private int count;

		private BoxCount(Box box) {
			this.box = box;
		}
	}
}
