package com.github.skjolber.packing.service.service.blockbeam;

import com.github.skjolber.packing.service.service.rule.PlacementGeometry;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/**
 * Bounded search over homogeneous blocks and a maximal-space cover whose bases
 * are fully supported.
 * It is intentionally independent of the core packagers so it can be evaluated
 * as an additional candidate without changing their established behaviour.
 */
public final class BlockBeamSearchPackager {

	private static final int DEFAULT_BEAM_WIDTH = 64;
	private static final int DEFAULT_BRANCHING = 48;
	private static final int MAX_ELITE_STATES = 12;

	private final SearchProfile searchProfile;
	private final BlockBeamMoveGenerator moveGenerator;
	private final BlockBeamSelector selector;

	public BlockBeamSearchPackager() {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING, PlacementSupport.DEFAULT_POLICY);
	}

	public BlockBeamSearchPackager(int beamWidth, int branching) {
		this(beamWidth, branching, PlacementSupport.DEFAULT_POLICY);
	}

	public BlockBeamSearchPackager(PlacementSupport.Policy supportPolicy) {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING, supportPolicy);
	}

	public BlockBeamSearchPackager(int beamWidth, int branching, PlacementSupport.Policy supportPolicy) {
		this(beamWidth, branching, supportPolicy, SearchProfile.BALANCED);
	}

	public BlockBeamSearchPackager(int beamWidth, int branching, PlacementSupport.Policy supportPolicy,
			SearchProfile searchProfile) {
		this.searchProfile = searchProfile;
		this.moveGenerator = new BlockBeamMoveGenerator(branching, supportPolicy, searchProfile);
		this.selector = new BlockBeamSelector(beamWidth, searchProfile);
	}

	public PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis) {
		return pack(sourceContainer, items, deadlineMillis, "");
	}

	public PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis, String logContext) {
		SearchSession session = newSession(sourceContainer, items, logContext);
		return session == null ? null : session.advance(deadlineMillis).result();
	}

	public SearchProgress probe(Container sourceContainer, List<BoxItem> items, int maximumExpandedStates,
			String logContext) {
		if (maximumExpandedStates <= 0) {
			throw new IllegalArgumentException("maximumExpandedStates must be positive");
		}
		SearchSession session = newSession(sourceContainer, items, logContext);
		return session == null ? SearchProgress.empty(items) : session.advanceStates(maximumExpandedStates);
	}

	public PackagerResult pack(Container sourceContainer, List<BoxItem> items, List<Placement> fixedPlacements,
			long deadlineMillis, String logContext) {
		SearchSession session = newSession(sourceContainer, items, fixedPlacements, logContext);
		return session == null ? null : session.advance(deadlineMillis).result();
	}

	SearchSession newSession(Container sourceContainer, List<BoxItem> items, String logContext) {
		return newSession(sourceContainer, items, List.of(), logContext);
	}

	SearchSession newSession(Container sourceContainer, List<BoxItem> items,
			List<Placement> fixedPlacements, String logContext) {
		if (items.isEmpty()) return null;
		Container container = sourceContainer.clone();
		List<Placement> fixed = List.copyOf(fixedPlacements);
		validateFixedPlacements(container, fixed);
		List<Block> catalog = BlockBeamBlockCatalog.generate(container, items);
		if (catalog.isEmpty()) return null;
		return new SearchSession(container, List.copyOf(items), catalog, fixed, logContext);
	}

	private PackagerResult result(Container container, State state, List<Placement> fixedPlacements, long elapsedMillis,
			int expandedStates, int generatedStates, String logContext) {
		List<Placement> placements = new ArrayList<>();
		for (BlockPlacement placement : state.placements()) placement.expandInto(placements);
		container.getStack().addAll(fixedPlacements);
		container.getStack().addAll(placements);
		System.out.println("packing-service BLOCK-BEAM" + logContext + " success=true elapsedMs=" + elapsedMillis
				+ " expanded=" + expandedStates + " generated=" + generatedStates
				+ " blocks=" + state.placements().size() + " placements=" + placements.size());
		return new PackagerResult(List.of(container), elapsedMillis, false);
	}

	final class SearchSession {
		private final Container container;
		private final List<BoxItem> items;
		private final List<Block> catalog;
		private final List<Placement> fixedPlacements;
		private final BlockBeamFixedPlacementIndex fixedPlacementIndex;
		private final String logContext;
		private final Set<String> visited = new HashSet<>();
		private List<State> beam;
		private int expandedStates;
		private int generatedStates;
		private int maximumPackedUnits;
		private long maximumPackedVolume;
		private final Map<String, State> eliteStates = new LinkedHashMap<>();
		private final int totalUnits;
		private final long fixedVolume;
		private final long totalVolume;
		private long activeMillis;
		private boolean exhausted;
		private PackagerResult completed;

		private SearchSession(Container container, List<BoxItem> items, List<Block> catalog,
				List<Placement> fixedPlacements, String logContext) {
			this.container = container;
			this.items = items;
			this.catalog = catalog;
			this.fixedPlacements = fixedPlacements;
			this.fixedPlacementIndex = new BlockBeamFixedPlacementIndex(fixedPlacements);
			this.logContext = logContext;
			int[] remaining = items.stream().mapToInt(BoxItem::getCount).toArray();
			this.totalUnits = Arrays.stream(remaining).sum();
			this.fixedVolume = fixedPlacements.stream().mapToLong(p -> p.getStackValue().getVolume()).sum();
			this.totalVolume = items.stream()
					.mapToLong(item -> item.getBox().getVolume() * (long) item.getCount()).sum();
			List<Space> spaces = BlockBeamSpaceManager.initialSpaces(container, fixedPlacements);
			if (spaces.isEmpty()) {
				this.beam = List.of();
				this.exhausted = true;
				return;
			}
			int fixedWeight = fixedWeight(fixedPlacements);
			State initial = new State(remaining, spaces, List.of(), fixedVolume, fixedWeight, 0, 0,
					feasibility(remaining, spaces, items));
			this.beam = List.of(initial);
			this.visited.add(initial.key());
		}

		SearchProgress advance(long deadlineMillis) {
			return advance(deadlineMillis, Integer.MAX_VALUE);
		}

		SearchProgress advanceStates(int maximumAdditionalStates) {
			if (maximumAdditionalStates <= 0) return progress();
			return advance(0L, maximumAdditionalStates);
		}

		private SearchProgress advance(long deadlineMillis, int maximumAdditionalStates) {
			if (completed != null || exhausted) return progress();
			long callStarted = System.nanoTime();
			int expandedAtStart = expandedStates;
			search:
			while (!beam.isEmpty() && !expired(deadlineMillis)
					&& expandedStates - expandedAtStart < maximumAdditionalStates) {
				List<State> next = new ArrayList<>();
				for (State state : beam) {
					if (expandedStates - expandedAtStart >= maximumAdditionalStates) break search;
					if (state.complete()) return complete(state, callStarted);
					expandedStates++;
					for (Move move : moveGenerator.moves(
							state, catalog, container, fixedPlacements, fixedPlacementIndex)) {
						State child = apply(state, move, container, items);
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
				next = selectBeam(next, container);
				for (State state : next) {
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

		private SearchProgress complete(State state, long callStarted) {
			activeMillis += elapsedMillis(callStarted);
			maximumPackedUnits = totalUnits;
			maximumPackedVolume = totalVolume;
			completed = result(container, state, fixedPlacements, activeMillis, expandedStates, generatedStates, logContext);
			return progress();
		}

		SearchProgress progress() {
			return new SearchProgress(completed, exhausted, maximumPackedUnits, totalUnits,
					maximumPackedVolume, totalVolume, eliteStates.size(),
					expandedStates, generatedStates, activeMillis);
		}

		PartialSolution bestPartial() {
			State best = eliteStates.values().stream()
					.max(BlockBeamStateEvaluator.partialComparator(fixedVolume)).orElse(null);
			if (best == null) return null;
			Container partial = container.clone();
			partial.getStack().addAll(fixedPlacements);
			List<Placement> placements = new ArrayList<>();
			for (BlockPlacement placement : best.placements()) placement.expandInto(placements);
			partial.getStack().addAll(placements);
			List<BoxItem> remaining = new ArrayList<>();
			for (int index = 0; index < best.remaining().length; index++) {
				int count = best.remaining()[index];
				if (count > 0) remaining.add(new BoxItem(items.get(index).getBox(), count));
			}
			return new PartialSolution(partial, List.copyOf(remaining),
					best.packedVolume(), fixedVolume + totalVolume,
					packedUnits(best), totalUnits);
		}

		private void trackElite(State state) {
			int units = packedUnits(state);
			long volume = state.packedVolume() - fixedVolume;
			maximumPackedUnits = Math.max(maximumPackedUnits, units);
			maximumPackedVolume = Math.max(maximumPackedVolume, volume);
			String shape = BlockBeamStateEvaluator.eliteShape(state, container);
			State previous = eliteStates.get(shape);
			if (previous == null || BlockBeamStateEvaluator.betterPartial(
					state, previous, fixedVolume)) eliteStates.put(shape, state);
			if (eliteStates.size() > MAX_ELITE_STATES) {
				String worst = eliteStates.entrySet().stream()
						.min(Map.Entry.comparingByValue(
								BlockBeamStateEvaluator.partialComparator(fixedVolume)))
						.map(Map.Entry::getKey).orElse(null);
				if (worst != null) eliteStates.remove(worst);
			}
		}

		private int packedUnits(State state) {
			return totalUnits - Arrays.stream(state.remaining()).sum();
		}
	}

	public record SearchProgress(PackagerResult result, boolean exhausted, int packedUnits,
			int totalUnits, long packedVolume, long totalVolume, int eliteStates,
			int expandedStates, int generatedStates, long activeMillis) {
		public boolean complete() {
			return result != null && result.isSuccess();
		}

		public double completionRatio() {
			return totalUnits == 0 ? 0.0 : packedUnits / (double) totalUnits;
		}

		public double volumeCompletionRatio() {
			return totalVolume == 0L ? 0.0 : packedVolume / (double) totalVolume;
		}

		private static SearchProgress empty(List<BoxItem> items) {
			int units = items.stream().mapToInt(BoxItem::getCount).sum();
			long volume = items.stream()
					.mapToLong(item -> item.getBox().getVolume() * (long) item.getCount()).sum();
			return new SearchProgress(null, true, 0, units, 0L, volume, 0, 0, 0, 0L);
		}
	}

	record PartialSolution(Container container, List<BoxItem> remainingItems,
			long loadedVolume, long targetVolume, int packedUnits, int totalUnits) {
		double volumeCompletionRatio() {
			return targetVolume == 0L ? 0.0 : loadedVolume / (double) targetVolume;
		}
	}

	private static void validateFixedPlacements(Container container, List<Placement> placements) {
		PlacementGeometry.Validation validation = PlacementGeometry.validate(container, placements);
		if (!validation.valid()) {
			throw new IllegalArgumentException("Invalid fixed placements: " + validation.message());
		}
	}

	private static int fixedWeight(List<Placement> placements) {
		long weight = 0L;
		for (Placement placement : placements) weight += placement.getBox().getWeight();
		return (int) Math.min(Integer.MAX_VALUE, weight);
	}

	private State apply(State state, Move move, Container container, List<BoxItem> items) {
		Space used = state.spaces().get(move.spaceIndex());
		Block block = move.block();
		int[] remaining = state.remaining().clone();
		remaining[block.itemIndex()] -= block.units();

		List<BlockPlacement> placements = new ArrayList<>(state.placements());
		placements.add(new BlockPlacement(block, used.x(), used.y(), used.z()));
		List<Space> spaces = BlockBeamSpaceManager.updateSpaces(
				state.spaces(), used.x(), used.y(), used.z(), block, container);
		return new State(remaining, spaces, placements,
				state.packedVolume() + block.volume(), state.weight() + block.weight(),
				Math.max(state.maxX(), used.x() + block.dx()),
				Math.max(state.maxZ(), used.z() + block.dz()), feasibility(remaining, spaces, items));
	}

	List<State> selectBeam(List<State> candidates, Container container) {
		return selector.select(candidates, container);
	}

	static Feasibility feasibility(int[] remaining, List<Space> spaces, List<BoxItem> items) {
		return BlockBeamStateEvaluator.feasibility(remaining, spaces, items);
	}

	private static boolean expired(long deadlineMillis) {
		return Thread.currentThread().isInterrupted()
				|| deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	record Block(int itemIndex, Box box, BoxStackValue orientation,
			int nx, int ny, int nz, int units, int dx, int dy, int dz, int weight, long volume) {
	}

	record BlockPlacement(Block block, int x, int y, int z) {
		void expandInto(List<Placement> target) {
			for (int ix = 0; ix < block.nx(); ix++) {
				for (int iy = 0; iy < block.ny(); iy++) {
					for (int iz = 0; iz < block.nz(); iz++) {
						target.add(new Placement(block.orientation(), -1,
								x + ix * block.orientation().getDx(),
								y + iy * block.orientation().getDy(),
								z + iz * block.orientation().getDz()));
					}
				}
			}
		}
	}

	record Move(int spaceIndex, Block block, boolean yFirst, double rank) {
		String key() {
			return spaceIndex + ":" + block.itemIndex() + ":" + block.orientation().getIndex()
					+ ":" + block.nx() + ":" + block.ny() + ":" + block.nz();
		}
	}

	public enum SearchProfile {
		BALANCED,
		VOLUME_FIRST,
		SPACE_EFFICIENCY,
		COMPACT_FRONT;

		String propertyValue() {
			return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
		}
	}

	record Feasibility(int strandedTypes, int scarceTypes,
			int minimumFitSpaces, int minimumClearance) {
	}

	record State(int[] remaining, List<Space> spaces, List<BlockPlacement> placements,
			long packedVolume, int weight, int maxX, int maxZ, Feasibility feasibility) {
		boolean complete() {
			for (int value : remaining) if (value != 0) return false;
			return true;
		}

		boolean intersects(int x, int y, int z, Block block) {
			int endX = x + block.dx();
			int endY = y + block.dy();
			int endZ = z + block.dz();
			for (BlockPlacement placement : placements) {
				Block placed = placement.block();
				if (x < placement.x() + placed.dx() && endX > placement.x()
						&& y < placement.y() + placed.dy() && endY > placement.y()
						&& z < placement.z() + placed.dz() && endZ > placement.z()) return true;
			}
			return false;
		}

		double score(Container container) {
			double fill = packedVolume / (double) container.getMaxLoadVolume();
			double length = maxX / (double) container.getLoadDx();
			double height = maxZ / (double) container.getLoadDz();
			long largestSpace = spaces.stream().mapToLong(Space::volume).max().orElse(0L);
			return fill * 1_000_000.0 + largestSpace / (double) container.getMaxLoadVolume() * 5_000.0
					+ feasibility.minimumFitSpaces() * 2_000.0
					+ feasibility.minimumClearance() * 5.0
					- feasibility.scarceTypes() * 500.0
					- feasibility.strandedTypes() * 10_000_000.0
					- spaces.size() * 15.0 - length * 100.0 - height * 50.0;
		}

		String key() {
			StringBuilder builder = new StringBuilder(Arrays.toString(remaining)).append('|');
			for (Space space : spaces) builder.append(space.key()).append(';');
			return builder.toString();
		}
	}

	record Space(int x, int y, int z, int dx, int dy, int dz) {
		static final Comparator<Space> ORDER = Comparator.comparingInt(Space::z)
				.thenComparingInt(Space::y).thenComparingInt(Space::x)
				.thenComparingInt(Space::dz).thenComparingInt(Space::dy).thenComparingInt(Space::dx);

		boolean fits(Block block) {
			return block.dx() <= dx && block.dy() <= dy && block.dz() <= dz;
		}

		long volume() {
			return (long) dx * dy * dz;
		}

		int endX() { return x + dx; }
		int endY() { return y + dy; }
		int endZ() { return z + dz; }

		boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			return x < maxX && endX() > minX && y < maxY && endY() > minY
					&& z < maxZ && endZ() > minZ;
		}

		boolean contains(Space other) {
			return z == other.z && x <= other.x && y <= other.y
					&& endX() >= other.endX() && endY() >= other.endY() && endZ() >= other.endZ();
		}

		String key() {
			return x + "," + y + "," + z + "," + dx + "," + dy + "," + dz;
		}

	}
}
