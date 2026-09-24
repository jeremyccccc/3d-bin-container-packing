package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.ep.points3d.DefaultPointCalculator3D;

/**
 * Bounded search over homogeneous blocks and a maximal-space cover whose bases
 * are fully supported.
 * It is intentionally independent of the core packagers so it can be evaluated
 * as an additional candidate without changing their established behaviour.
 */
final class BlockBeamSearchPackager {

	private static final int DEFAULT_BEAM_WIDTH = 64;
	private static final int DEFAULT_BRANCHING = 48;
	private static final int MAX_VERTICAL_UNITS = 5;
	private static final int TRACKED_FIT_SPACES = 4;
	private static final int MAX_ELITE_STATES = 12;

	private final int beamWidth;
	private final int branching;
	private final PlacementSupport.Policy supportPolicy;
	private final SearchProfile searchProfile;

	BlockBeamSearchPackager() {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING, PlacementSupport.DEFAULT_POLICY);
	}

	BlockBeamSearchPackager(int beamWidth, int branching) {
		this(beamWidth, branching, PlacementSupport.DEFAULT_POLICY);
	}

	BlockBeamSearchPackager(PlacementSupport.Policy supportPolicy) {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING, supportPolicy);
	}

	BlockBeamSearchPackager(int beamWidth, int branching, PlacementSupport.Policy supportPolicy) {
		this(beamWidth, branching, supportPolicy, SearchProfile.BALANCED);
	}

	BlockBeamSearchPackager(int beamWidth, int branching, PlacementSupport.Policy supportPolicy,
			SearchProfile searchProfile) {
		this.beamWidth = beamWidth;
		this.branching = branching;
		this.supportPolicy = supportPolicy;
		this.searchProfile = searchProfile;
	}

	PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis) {
		return pack(sourceContainer, items, deadlineMillis, "");
	}

	PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis, String logContext) {
		SearchSession session = newSession(sourceContainer, items, logContext);
		return session == null ? null : session.advance(deadlineMillis).result();
	}

	PackagerResult pack(Container sourceContainer, List<BoxItem> items, List<Placement> fixedPlacements,
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
		List<Block> catalog = generateBlocks(container, items);
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
		private final FixedPlacementIndex fixedPlacementIndex;
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
			this.fixedPlacementIndex = new FixedPlacementIndex(fixedPlacements);
			this.logContext = logContext;
			int[] remaining = items.stream().mapToInt(BoxItem::getCount).toArray();
			this.totalUnits = Arrays.stream(remaining).sum();
			this.fixedVolume = fixedPlacements.stream().mapToLong(p -> p.getStackValue().getVolume()).sum();
			this.totalVolume = items.stream()
					.mapToLong(item -> item.getBox().getVolume() * (long) item.getCount()).sum();
			List<Space> spaces = initialSpaces(container, fixedPlacements);
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
			if (completed != null || exhausted) return progress();
			long callStarted = System.nanoTime();
			while (!beam.isEmpty() && !expired(deadlineMillis)) {
				List<State> next = new ArrayList<>();
				for (State state : beam) {
					if (state.complete()) return complete(state, callStarted);
					expandedStates++;
					for (Move move : moves(state, catalog, container, fixedPlacements, fixedPlacementIndex)) {
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
					.max(partialComparator(fixedVolume)).orElse(null);
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
			String shape = eliteShape(state, container);
			State previous = eliteStates.get(shape);
			if (previous == null || betterPartial(state, previous, fixedVolume)) eliteStates.put(shape, state);
			if (eliteStates.size() > MAX_ELITE_STATES) {
				String worst = eliteStates.entrySet().stream()
						.min(Map.Entry.comparingByValue(partialComparator(fixedVolume)))
						.map(Map.Entry::getKey).orElse(null);
				if (worst != null) eliteStates.remove(worst);
			}
		}

		private int packedUnits(State state) {
			return totalUnits - Arrays.stream(state.remaining()).sum();
		}
	}

	record SearchProgress(PackagerResult result, boolean exhausted, int packedUnits,
			int totalUnits, long packedVolume, long totalVolume, int eliteStates,
			int expandedStates, int generatedStates, long activeMillis) {
		boolean complete() {
			return result != null && result.isSuccess();
		}

		double completionRatio() {
			return totalUnits == 0 ? 0.0 : packedUnits / (double) totalUnits;
		}

		double volumeCompletionRatio() {
			return totalVolume == 0L ? 0.0 : packedVolume / (double) totalVolume;
		}
	}

	record PartialSolution(Container container, List<BoxItem> remainingItems,
			long loadedVolume, long targetVolume, int packedUnits, int totalUnits) {
		double volumeCompletionRatio() {
			return targetVolume == 0L ? 0.0 : loadedVolume / (double) targetVolume;
		}
	}

	private List<Move> moves(State state, List<Block> catalog, Container container,
			List<Placement> fixedPlacements, FixedPlacementIndex fixedPlacementIndex) {
		PriorityQueue<Move> best = new PriorityQueue<>(Comparator.comparingDouble(Move::rank));
		Map<Integer, Move> bestByItem = new HashMap<>();
		for (int spaceIndex = 0; spaceIndex < state.spaces().size(); spaceIndex++) {
			Space space = state.spaces().get(spaceIndex);
			for (Block block : catalog) {
				if (state.remaining()[block.itemIndex()] < block.units() || !space.fits(block)
						|| state.intersects(space.x(), space.y(), space.z(), block)
						|| fixedPlacementIndex.intersects(space.x(), space.y(), space.z(), block)) continue;
				// A normal full-container search is validated as a complete layout by
				// PlacementSupport. Pruning partially built layouts here changes the
				// beam path and can discard a layout which becomes valid after later
				// placements. Fixed-layout insertion is different: the existing cargo
				// is immutable, so support must be checked while exploring its gaps.
				if (!fixedPlacements.isEmpty() && !isSupported(space.x(), space.y(), space.z(), block,
						fixedPlacements, state.placements(), supportPolicy)) continue;
				if ((long) state.weight() + block.weight() > container.getMaxLoadWeight()) continue;
				double rank = moveRank(space, block, container, searchProfile);
				Move move = new Move(spaceIndex, block, false, rank);
				add(best, move);
				bestByItem.merge(block.itemIndex(), move,
						(a, b) -> a.rank() >= b.rank() ? a : b);
			}
		}
		Map<String, Move> unique = new LinkedHashMap<>();
		for (Move move : best) unique.put(move.key(), move);
		for (Move move : bestByItem.values()) unique.put(move.key(), move);
		List<Move> result = new ArrayList<>(unique.values());
		result.sort(Comparator.comparingDouble(Move::rank).reversed());
		return result;
	}

	private void add(PriorityQueue<Move> best, Move move) {
		if (best.size() < branching) {
			best.add(move);
		} else if (move.rank() > best.peek().rank()) {
			best.poll();
			best.add(move);
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

	private static List<Space> initialSpaces(Container container, List<Placement> fixedPlacements) {
		if (fixedPlacements.isEmpty()) {
			return List.of(new Space(0, 0, 0,
					container.getLoadDx(), container.getLoadDy(), container.getLoadDz()));
		}
		List<Placement> ordered = fixedPlacements.stream()
				.sorted(Comparator.comparingInt(Placement::getAbsoluteZ)
						.thenComparingInt(Placement::getAbsoluteY)
						.thenComparingInt(Placement::getAbsoluteX))
				.toList();
		DefaultPointCalculator3D calculator = new DefaultPointCalculator3D(false, ordered.size() + 1);
		calculator.clearToSize(container.getLoadDx(), container.getLoadDy(), container.getLoadDz());
		for (int i = 0; i < ordered.size(); i++) {
			if (!calculator.addObstacle(ordered.get(i))) {
				throw new IllegalArgumentException("Unable to add fixed placement obstacle #" + i);
			}
		}
		List<Space> spaces = new ArrayList<>();
		for (Point point : calculator.getAll()) {
			addSpace(spaces, point.getMinX(), point.getMinY(), point.getMinZ(),
					point.getDx(), point.getDy(), point.getDz());
		}
		return normalize(spaces);
	}

	private static boolean isSupported(int x, int y, int z, Block block,
			List<Placement> fixedPlacements, List<BlockPlacement> placedBlocks,
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
			List<Placement> fixedPlacements, List<BlockPlacement> placedBlocks,
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
		for (BlockPlacement placement : placedBlocks) {
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

	private static double moveRank(Space space, Block block, Container container, SearchProfile profile) {
		double fill = block.volume() / (double) space.volume();
		double footprint = (long) block.dx() * block.dy() / (double) ((long) space.dx() * space.dy());
		double wall = (space.x() == 0 ? 1 : 0) + (space.y() == 0 ? 1 : 0)
				+ (space.x() + block.dx() == container.getLoadDx() ? 1 : 0)
				+ (space.y() + block.dy() == container.getLoadDy() ? 1 : 0);
		double balanced = block.volume() / (double) container.getMaxLoadVolume() * 10_000_000.0
				+ block.units() * 100.0
				+ fill * 100_000.0 + footprint * 20_000.0 + wall * 1_000.0
				- space.z() * 0.01;
		return switch (profile) {
			case BALANCED -> balanced;
			case VOLUME_FIRST -> balanced + block.volume() / (double) container.getMaxLoadVolume() * 8_000_000.0;
			case SPACE_EFFICIENCY -> balanced + fill * 180_000.0 + footprint * 80_000.0;
			case COMPACT_FRONT -> balanced + wall * 8_000.0
					- space.x() / (double) container.getLoadDx() * 20_000.0
					- space.z() / (double) container.getLoadDz() * 10_000.0;
		};
	}

	private State apply(State state, Move move, Container container, List<BoxItem> items) {
		Space used = state.spaces().get(move.spaceIndex());
		Block block = move.block();
		int[] remaining = state.remaining().clone();
		remaining[block.itemIndex()] -= block.units();

		List<BlockPlacement> placements = new ArrayList<>(state.placements());
		placements.add(new BlockPlacement(block, used.x(), used.y(), used.z()));
		List<Space> spaces = updateSpaces(state.spaces(), used.x(), used.y(), used.z(), block, container);
		return new State(remaining, spaces, placements,
				state.packedVolume() + block.volume(), state.weight() + block.weight(),
				Math.max(state.maxX(), used.x() + block.dx()),
				Math.max(state.maxZ(), used.z() + block.dz()), feasibility(remaining, spaces, items));
	}

	List<State> selectBeam(List<State> candidates, Container container) {
		if (candidates.size() <= beamWidth) {
			candidates.sort(stateComparator(container, searchProfile));
			return candidates;
		}

		Comparator<State> overall = stateComparator(container, searchProfile);
		Comparator<State> criticalSpace = Comparator
				.comparingInt((State state) -> state.feasibility().strandedTypes())
				.thenComparing(Comparator.comparingInt(
						(State state) -> state.feasibility().minimumFitSpaces()).reversed())
				.thenComparingInt(state -> state.feasibility().scarceTypes())
				.thenComparing(Comparator.comparingInt(
						(State state) -> state.feasibility().minimumClearance()).reversed())
				.thenComparing(overall);
		Comparator<State> openSpace = Comparator
				.comparingLong((State state) -> largestSpace(state.spaces())).reversed()
				.thenComparingInt(state -> state.spaces().size())
				.thenComparing(overall);

		LinkedHashSet<State> selected = new LinkedHashSet<>(beamWidth * 2);
		addBest(selected, candidates, overall,
				availableQuota(selected, Math.max(1, beamWidth / 2)));
		addBest(selected, candidates, criticalSpace,
				availableQuota(selected, Math.max(1, beamWidth / 4)));
		addBest(selected, candidates, openSpace,
				availableQuota(selected, Math.max(1, beamWidth / 8)));
		addShapeDiversity(selected, candidates, overall, container,
				availableQuota(selected, Math.max(1, beamWidth / 8)));
		addBest(selected, candidates, overall, beamWidth - selected.size());
		return new ArrayList<>(selected);
	}

	private int availableQuota(Set<State> selected, int requested) {
		return Math.max(0, Math.min(requested, beamWidth - selected.size()));
	}

	private static void addBest(Set<State> selected, List<State> candidates,
			Comparator<State> comparator, int count) {
		if (count <= 0) return;
		List<State> sorted = new ArrayList<>(candidates);
		sorted.sort(comparator);
		int added = 0;
		for (State state : sorted) {
			if (selected.add(state) && ++added >= count) return;
		}
	}

	private static void addShapeDiversity(Set<State> selected, List<State> candidates,
			Comparator<State> comparator, Container container, int count) {
		if (count <= 0) return;
		List<State> sorted = new ArrayList<>(candidates);
		sorted.sort(comparator);
		Set<String> buckets = new HashSet<>();
		int added = 0;
		for (State state : sorted) {
			int lengthBucket = 4 * state.maxX() / Math.max(1, container.getLoadDx());
			int heightBucket = 4 * state.maxZ() / Math.max(1, container.getLoadDz());
			int spaceBucket = Math.min(7, state.spaces().size() / 4);
			String bucket = lengthBucket + ":" + heightBucket + ":" + spaceBucket;
			if (selected.contains(state) || !buckets.add(bucket)) continue;
			selected.add(state);
			if (++added >= count) return;
		}
	}

	private static long largestSpace(List<Space> spaces) {
		long largest = 0L;
		for (Space space : spaces) largest = Math.max(largest, space.volume());
		return largest;
	}

	static Feasibility feasibility(int[] remaining, List<Space> spaces, List<BoxItem> items) {
		int strandedTypes = 0;
		int scarceTypes = 0;
		int minimumFitSpaces = Integer.MAX_VALUE;
		int minimumClearance = Integer.MAX_VALUE;
		for (int itemIndex = 0; itemIndex < remaining.length; itemIndex++) {
			if (remaining[itemIndex] == 0) continue;
			Box box = items.get(itemIndex).getBox();
			int fitSpaces = 0;
			int bestClearance = -1;
			for (Space space : spaces) {
				int clearance = clearance(box, space);
				if (clearance < 0) continue;
				fitSpaces++;
				bestClearance = Math.max(bestClearance, clearance);
				if (fitSpaces >= TRACKED_FIT_SPACES) break;
			}
			if (fitSpaces == 0) {
				strandedTypes++;
				minimumFitSpaces = 0;
				minimumClearance = -1;
				continue;
			}
			minimumFitSpaces = Math.min(minimumFitSpaces, fitSpaces);
			minimumClearance = Math.min(minimumClearance, bestClearance);
			if (fitSpaces <= 2) scarceTypes++;
		}
		if (minimumFitSpaces == Integer.MAX_VALUE) minimumFitSpaces = TRACKED_FIT_SPACES;
		if (minimumClearance == Integer.MAX_VALUE) minimumClearance = 0;
		return new Feasibility(strandedTypes, scarceTypes, minimumFitSpaces, minimumClearance);
	}

	private static int clearance(Box box, Space space) {
		int best = -1;
		for (BoxStackValue orientation : box.getStackValues()) {
			if (orientation.getDx() > space.dx() || orientation.getDy() > space.dy()
					|| orientation.getDz() > space.dz()) continue;
			best = Math.max(best, Math.min(space.dx() - orientation.getDx(),
					Math.min(space.dy() - orientation.getDy(), space.dz() - orientation.getDz())));
		}
		return best;
	}

	private static List<Space> updateSpaces(List<Space> source, int x, int y, int z,
			Block block, Container container) {
		int endX = x + block.dx();
		int endY = y + block.dy();
		int endZ = z + block.dz();
		List<Space> spaces = new ArrayList<>();
		for (Space space : source) {
			if (!space.intersects(x, y, z, endX, endY, endZ)) {
				spaces.add(space);
				continue;
			}
			int intersectionMinX = Math.max(space.x(), x);
			int intersectionMaxX = Math.min(space.endX(), endX);
			int intersectionMinY = Math.max(space.y(), y);
			int intersectionMaxY = Math.min(space.endY(), endY);
			int intersectionMinZ = Math.max(space.z(), z);
			addSpace(spaces, space.x(), space.y(), space.z(),
					intersectionMinX - space.x(), space.dy(), space.dz());
			addSpace(spaces, intersectionMaxX, space.y(), space.z(),
					space.endX() - intersectionMaxX, space.dy(), space.dz());
			addSpace(spaces, space.x(), space.y(), space.z(),
					space.dx(), intersectionMinY - space.y(), space.dz());
			addSpace(spaces, space.x(), intersectionMaxY, space.z(),
					space.dx(), space.endY() - intersectionMaxY, space.dz());
			addSpace(spaces, space.x(), space.y(), space.z(),
					space.dx(), space.dy(), intersectionMinZ - space.z());
		}
		addSpace(spaces, x, y, endZ, block.dx(), block.dy(), container.getLoadDz() - endZ);
		return normalize(spaces);
	}

	private static void addSpace(List<Space> spaces, int x, int y, int z, int dx, int dy, int dz) {
		if (dx > 0 && dy > 0 && dz > 0) spaces.add(new Space(x, y, z, dx, dy, dz));
	}

	private static List<Space> normalize(List<Space> input) {
		List<Space> spaces = new ArrayList<>(new LinkedHashMap<>(input.stream()
				.collect(java.util.stream.Collectors.toMap(Space::key, s -> s, (a, b) -> a,
						LinkedHashMap::new))).values());
		for (int i = spaces.size() - 1; i >= 0; i--) {
			Space candidate = spaces.get(i);
			for (int j = 0; j < spaces.size(); j++) {
				if (i != j && spaces.get(j).contains(candidate)) {
					spaces.remove(i);
					break;
				}
			}
		}
		spaces.sort(Space.ORDER);
		return spaces;
	}

	private static List<Block> generateBlocks(Container container, List<BoxItem> items) {
		Map<String, Block> unique = new LinkedHashMap<>();
		for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
			BoxItem item = items.get(itemIndex);
			for (BoxStackValue orientation : item.getBox().getStackValues()) {
				int maxX = Math.min(item.getCount(), container.getLoadDx() / orientation.getDx());
				int maxY = Math.min(item.getCount(), container.getLoadDy() / orientation.getDy());
				int maxZ = Math.min(Math.min(item.getCount(), MAX_VERTICAL_UNITS),
						container.getLoadDz() / orientation.getDz());
				if (maxX == 0 || maxY == 0 || maxZ == 0) continue;
				for (int z = 1; z <= maxZ; z++) {
					for (int y = 1; y <= maxY; y++) {
						int x = Math.min(maxX, item.getCount() / (y * z));
						addBlock(unique, itemIndex, item.getBox(), orientation, x, y, z);
						addBlock(unique, itemIndex, item.getBox(), orientation, 1, y, z);
					}
					for (int x = 1; x <= maxX; x++) {
						int y = Math.min(maxY, item.getCount() / (x * z));
						addBlock(unique, itemIndex, item.getBox(), orientation, x, y, z);
					}
				}
			}
		}
		List<Block> blocks = new ArrayList<>(unique.values());
		blocks.sort(Comparator.comparingInt(Block::units).reversed()
				.thenComparing(Comparator.comparingLong(Block::volume).reversed())
				.thenComparingInt(Block::dz));
		return blocks;
	}

	private static void addBlock(Map<String, Block> blocks, int itemIndex, Box box,
			BoxStackValue orientation, int nx, int ny, int nz) {
		if (nx < 1 || ny < 1 || nz < 1) return;
		int units = nx * ny * nz;
		String key = itemIndex + ":" + orientation.getIndex() + ":" + nx + ":" + ny + ":" + nz;
		blocks.putIfAbsent(key, new Block(itemIndex, box, orientation, nx, ny, nz,
				units, orientation.getDx() * nx, orientation.getDy() * ny,
				orientation.getDz() * nz, Math.multiplyExact(box.getWeight(), units),
				Math.multiplyExact(box.getVolume(), units)));
	}

	private static Comparator<State> stateComparator(Container container, SearchProfile profile) {
		Comparator<State> feasibilityStage = Comparator
				.comparingInt((State state) -> state.feasibility().strandedTypes())
				.thenComparingInt(state -> state.feasibility().scarceTypes())
				.thenComparing(Comparator.comparingInt(
						(State state) -> state.feasibility().minimumFitSpaces()).reversed())
				.thenComparing(Comparator.comparingInt(
						(State state) -> state.feasibility().minimumClearance()).reversed());
		Comparator<State> qualityStage = switch (profile) {
			case BALANCED -> Comparator.comparingDouble((State state) -> state.score(container)).reversed();
			case VOLUME_FIRST -> Comparator.comparingLong(State::packedVolume).reversed()
					.thenComparing(Comparator.comparingLong(
							(State state) -> largestSpace(state.spaces())).reversed());
			case SPACE_EFFICIENCY -> Comparator
					.comparingLong((State state) -> largestSpace(state.spaces())).reversed()
					.thenComparingInt(state -> state.spaces().size())
					.thenComparing(Comparator.comparingLong(State::packedVolume).reversed());
			case COMPACT_FRONT -> Comparator.comparingInt(State::maxX)
					.thenComparingInt(State::maxZ)
					.thenComparing(Comparator.comparingLong(State::packedVolume).reversed());
		};
		// Keep the main lane focused on packing quality. A separate quota in
		// selectBeam uses feasibility-first ordering, so neither objective can
		// eliminate the other before the next level.
		return qualityStage.thenComparing(feasibilityStage);
	}

	private static Comparator<State> partialComparator(long fixedVolume) {
		return Comparator.comparingLong((State state) -> state.packedVolume() - fixedVolume)
				.thenComparingInt(state -> -Arrays.stream(state.remaining()).sum())
				.thenComparingLong(state -> largestSpace(state.spaces()));
	}

	private static boolean betterPartial(State candidate, State current, long fixedVolume) {
		return partialComparator(fixedVolume).compare(candidate, current) > 0;
	}

	private static String eliteShape(State state, Container container) {
		int lengthBucket = 8 * state.maxX() / Math.max(1, container.getLoadDx());
		int heightBucket = 8 * state.maxZ() / Math.max(1, container.getLoadDz());
		int spaceBucket = Math.min(15, state.spaces().size() / 3);
		int scarceBucket = Math.min(7, state.feasibility().scarceTypes());
		return lengthBucket + ":" + heightBucket + ":" + spaceBucket + ":" + scarceBucket;
	}

	private static boolean expired(long deadlineMillis) {
		return Thread.currentThread().isInterrupted()
				|| deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	private record Block(int itemIndex, Box box, BoxStackValue orientation,
			int nx, int ny, int nz, int units, int dx, int dy, int dz, int weight, long volume) {
	}

	private record BlockPlacement(Block block, int x, int y, int z) {
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

	private record Move(int spaceIndex, Block block, boolean yFirst, double rank) {
		String key() {
			return spaceIndex + ":" + block.itemIndex() + ":" + block.orientation().getIndex()
					+ ":" + block.nx() + ":" + block.ny() + ":" + block.nz();
		}
	}

	private record SupportRectangle(int minX, int minY, int maxX, int maxY) {
		boolean contains(double x, double y) {
			return x >= minX && x < maxX && y >= minY && y < maxY;
		}
	}

	private record SupportInterval(int min, int max) {
	}

	private static final class FixedPlacementIndex {
		private static final int CELL_SIZE = 1_000;
		private final Map<Long, List<Placement>> cells = new HashMap<>();

		private FixedPlacementIndex(List<Placement> placements) {
			for (Placement placement : placements) {
				for (int ix = placement.getAbsoluteX() / CELL_SIZE;
						ix <= placement.getAbsoluteEndX() / CELL_SIZE; ix++) {
					for (int iy = placement.getAbsoluteY() / CELL_SIZE;
							iy <= placement.getAbsoluteEndY() / CELL_SIZE; iy++) {
						for (int iz = placement.getAbsoluteZ() / CELL_SIZE;
								iz <= placement.getAbsoluteEndZ() / CELL_SIZE; iz++) {
							cells.computeIfAbsent(key(ix, iy, iz), ignored -> new ArrayList<>()).add(placement);
						}
					}
				}
			}
		}

		private boolean intersects(int x, int y, int z, Block block) {
			int endX = x + block.dx();
			int endY = y + block.dy();
			int endZ = z + block.dz();
			for (int ix = x / CELL_SIZE; ix <= (endX - 1) / CELL_SIZE; ix++) {
				for (int iy = y / CELL_SIZE; iy <= (endY - 1) / CELL_SIZE; iy++) {
					for (int iz = z / CELL_SIZE; iz <= (endZ - 1) / CELL_SIZE; iz++) {
						for (Placement placement : cells.getOrDefault(key(ix, iy, iz), List.of())) {
							if (x < placement.getAbsoluteEndX() + 1 && endX > placement.getAbsoluteX()
									&& y < placement.getAbsoluteEndY() + 1 && endY > placement.getAbsoluteY()
									&& z < placement.getAbsoluteEndZ() + 1 && endZ > placement.getAbsoluteZ()) return true;
						}
					}
				}
			}
			return false;
		}

		private static long key(int x, int y, int z) {
			return ((long) x << 42) | ((long) y << 21) | z;
		}
	}

	enum SearchProfile {
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
