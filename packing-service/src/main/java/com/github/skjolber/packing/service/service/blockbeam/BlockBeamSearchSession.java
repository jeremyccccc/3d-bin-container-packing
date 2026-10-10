package com.github.skjolber.packing.service.service.blockbeam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/** Mutable state for one resumable Block Beam search. */
final class BlockBeamSearchSession {

	private static final int MAX_ELITE_STATES = 12;

	private final Container container;
	private final List<BoxItem> items;
	private final List<BlockBeamSearchPackager.Block> catalog;
	private final List<Placement> fixedPlacements;
	private final BlockBeamFixedPlacementIndex fixedPlacementIndex;
	private final String logContext;
	private final BlockBeamSearchPackager.SearchProfile searchProfile;
	private final BlockBeamMoveGenerator moveGenerator;
	private final BlockBeamSelector selector;
	private final Set<String> visited = new HashSet<>();
	private List<BlockBeamSearchPackager.State> beam;
	private int expandedStates;
	private int generatedStates;
	private int maximumPackedUnits;
	private long maximumPackedVolume;
	private final Map<String, BlockBeamSearchPackager.State> eliteStates = new LinkedHashMap<>();
	private final int totalUnits;
	private final long fixedVolume;
	private final long totalVolume;
	private long activeMillis;
	private boolean exhausted;
	private PackagerResult completed;

	BlockBeamSearchSession(Container container, List<BoxItem> items,
			List<BlockBeamSearchPackager.Block> catalog, List<Placement> fixedPlacements,
			String logContext, BlockBeamSearchPackager.SearchProfile searchProfile,
			BlockBeamMoveGenerator moveGenerator, BlockBeamSelector selector) {
		this.container = container;
		this.items = items;
		this.catalog = catalog;
		this.fixedPlacements = fixedPlacements;
		this.fixedPlacementIndex = new BlockBeamFixedPlacementIndex(fixedPlacements);
		this.logContext = logContext;
		this.searchProfile = searchProfile;
		this.moveGenerator = moveGenerator;
		this.selector = selector;
		int[] remaining = items.stream().mapToInt(BoxItem::getCount).toArray();
		this.totalUnits = Arrays.stream(remaining).sum();
		this.fixedVolume = fixedPlacements.stream().mapToLong(p -> p.getStackValue().getVolume()).sum();
		this.totalVolume = items.stream()
				.mapToLong(item -> item.getBox().getVolume() * (long) item.getCount()).sum();
		List<BlockBeamSearchPackager.Space> spaces = BlockBeamSpaceManager.initialSpaces(container, fixedPlacements);
		if (spaces.isEmpty()) {
			this.beam = List.of();
			this.exhausted = true;
			return;
		}
		int fixedWeight = fixedWeight(fixedPlacements);
		BlockBeamSearchPackager.State initial = new BlockBeamSearchPackager.State(
				remaining, spaces, List.of(), fixedVolume, fixedWeight, 0, 0,
				BlockBeamStateEvaluator.feasibility(remaining, spaces, items));
		this.beam = List.of(initial);
		this.visited.add(initial.key());
	}

	BlockBeamSearchPackager.SearchProgress advance(long deadlineMillis) {
		return advance(deadlineMillis, Integer.MAX_VALUE);
	}

	BlockBeamSearchPackager.SearchProgress advanceStates(int maximumAdditionalStates) {
		if (maximumAdditionalStates <= 0) return progress();
		return advance(0L, maximumAdditionalStates);
	}

	private BlockBeamSearchPackager.SearchProgress advance(long deadlineMillis, int maximumAdditionalStates) {
		if (completed != null || exhausted) return progress();
		long callStarted = System.nanoTime();
		int expandedAtStart = expandedStates;
		search:
		while (!beam.isEmpty() && !expired(deadlineMillis)
				&& expandedStates - expandedAtStart < maximumAdditionalStates) {
			List<BlockBeamSearchPackager.State> next = new ArrayList<>();
			for (BlockBeamSearchPackager.State state : beam) {
				if (expandedStates - expandedAtStart >= maximumAdditionalStates) break search;
				if (state.complete()) return complete(state, callStarted);
				expandedStates++;
				for (BlockBeamSearchPackager.Move move : moveGenerator.moves(
						state, catalog, container, fixedPlacements, fixedPlacementIndex)) {
					BlockBeamSearchPackager.State child = apply(state, move);
					generatedStates++;
					trackElite(child);
					if (child.feasibility().strandedTypes() == 0 && visited.add(child.key())) next.add(child);
				}
			}
			if (next.isEmpty()) {
				exhausted = true;
				beam = List.of();
				break;
			}
			next = selector.select(next, container);
			for (BlockBeamSearchPackager.State state : next) {
				if (state.complete()) return complete(state, callStarted);
			}
			beam = next;
		}
		activeMillis += elapsedMillis(callStarted);
		System.out.println("packing-service BLOCK-BEAM" + logContext
				+ " profile=" + searchProfile.propertyValue()
				+ " success=false status=" + (exhausted ? "exhausted" : "paused")
				+ " elapsedMs=" + activeMillis + " expanded=" + expandedStates
				+ " generated=" + generatedStates + " blockTypes=" + catalog.size()
				+ " packedUnits=" + maximumPackedUnits + "/" + totalUnits
				+ " packedVolume=" + maximumPackedVolume + "/" + totalVolume
				+ " elites=" + eliteStates.size());
		return progress();
	}

	private BlockBeamSearchPackager.SearchProgress complete(
			BlockBeamSearchPackager.State state, long callStarted) {
		activeMillis += elapsedMillis(callStarted);
		maximumPackedUnits = totalUnits;
		maximumPackedVolume = totalVolume;
		completed = result(state);
		return progress();
	}

	BlockBeamSearchPackager.SearchProgress progress() {
		return new BlockBeamSearchPackager.SearchProgress(completed, exhausted, maximumPackedUnits, totalUnits,
				maximumPackedVolume, totalVolume, eliteStates.size(),
				expandedStates, generatedStates, activeMillis);
	}

	BlockBeamSearchPackager.PartialSolution bestPartial() {
		BlockBeamSearchPackager.State best = eliteStates.values().stream()
				.max(BlockBeamStateEvaluator.partialComparator(fixedVolume)).orElse(null);
		if (best == null) return null;
		Container partial = container.clone();
		partial.getStack().addAll(fixedPlacements);
		List<Placement> placements = new ArrayList<>();
		for (BlockBeamSearchPackager.BlockPlacement placement : best.placements()) {
			placement.expandInto(placements);
		}
		partial.getStack().addAll(placements);
		List<BoxItem> remaining = new ArrayList<>();
		for (int index = 0; index < best.remaining().length; index++) {
			int count = best.remaining()[index];
			if (count > 0) remaining.add(new BoxItem(items.get(index).getBox(), count));
		}
		return new BlockBeamSearchPackager.PartialSolution(partial, List.copyOf(remaining),
				best.packedVolume(), fixedVolume + totalVolume, packedUnits(best), totalUnits);
	}

	private PackagerResult result(BlockBeamSearchPackager.State state) {
		List<Placement> placements = new ArrayList<>();
		for (BlockBeamSearchPackager.BlockPlacement placement : state.placements()) {
			placement.expandInto(placements);
		}
		container.getStack().addAll(fixedPlacements);
		container.getStack().addAll(placements);
		System.out.println("packing-service BLOCK-BEAM" + logContext + " success=true elapsedMs=" + activeMillis
				+ " expanded=" + expandedStates + " generated=" + generatedStates
				+ " blocks=" + state.placements().size() + " placements=" + placements.size());
		return new PackagerResult(List.of(container), activeMillis, false);
	}

	private BlockBeamSearchPackager.State apply(BlockBeamSearchPackager.State state,
			BlockBeamSearchPackager.Move move) {
		BlockBeamSearchPackager.Space used = state.spaces().get(move.spaceIndex());
		BlockBeamSearchPackager.Block block = move.block();
		int[] remaining = state.remaining().clone();
		remaining[block.itemIndex()] -= block.units();
		List<BlockBeamSearchPackager.BlockPlacement> placements = new ArrayList<>(state.placements());
		placements.add(new BlockBeamSearchPackager.BlockPlacement(block, used.x(), used.y(), used.z()));
		List<BlockBeamSearchPackager.Space> spaces = BlockBeamSpaceManager.updateSpaces(
				state.spaces(), used.x(), used.y(), used.z(), block, container);
		return new BlockBeamSearchPackager.State(remaining, spaces, placements,
				state.packedVolume() + block.volume(), state.weight() + block.weight(),
				Math.max(state.maxX(), used.x() + block.dx()),
				Math.max(state.maxZ(), used.z() + block.dz()),
				BlockBeamStateEvaluator.feasibility(remaining, spaces, items));
	}

	private void trackElite(BlockBeamSearchPackager.State state) {
		int units = packedUnits(state);
		long volume = state.packedVolume() - fixedVolume;
		maximumPackedUnits = Math.max(maximumPackedUnits, units);
		maximumPackedVolume = Math.max(maximumPackedVolume, volume);
		String shape = BlockBeamStateEvaluator.eliteShape(state, container);
		BlockBeamSearchPackager.State previous = eliteStates.get(shape);
		if (previous == null || BlockBeamStateEvaluator.betterPartial(state, previous, fixedVolume)) {
			eliteStates.put(shape, state);
		}
		if (eliteStates.size() > MAX_ELITE_STATES) {
			String worst = eliteStates.entrySet().stream()
					.min(Map.Entry.comparingByValue(BlockBeamStateEvaluator.partialComparator(fixedVolume)))
					.map(Map.Entry::getKey).orElse(null);
			if (worst != null) eliteStates.remove(worst);
		}
	}

	private int packedUnits(BlockBeamSearchPackager.State state) {
		return totalUnits - Arrays.stream(state.remaining()).sum();
	}

	private static int fixedWeight(List<Placement> placements) {
		long weight = 0L;
		for (Placement placement : placements) weight += placement.getBox().getWeight();
		return (int) Math.min(Integer.MAX_VALUE, weight);
	}

	private static boolean expired(long deadlineMillis) {
		return Thread.currentThread().isInterrupted()
				|| deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}
