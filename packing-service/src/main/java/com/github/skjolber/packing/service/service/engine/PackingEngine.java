package com.github.skjolber.packing.service.service.engine;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.service.PackingPlan;
import com.github.skjolber.packing.service.service.assignment.WholeOrderAssignmentStrategy;
import com.github.skjolber.packing.service.service.blockbeam.BlockBeamPackingStrategy;
import com.github.skjolber.packing.service.service.compaction.AutomaticContainerFallback;
import com.github.skjolber.packing.service.service.logging.PackingLogContext;
import com.github.skjolber.packing.service.service.rule.DoorSideRuleSupport;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

@Component
public class PackingEngine {
	private static final int DEFAULT_CANDIDATE_PROBE_WINDOW = 8;
	private static final int DEFAULT_CANDIDATE_PROBE_STATES = 8;

	private final WholeOrderPackingCoordinator wholeOrderCoordinator;
	private final BlockBeamPackingStrategy blockBeamStrategy;
	private final LegacyContainerPackingStrategy legacyStrategy;
	private final PackingEngineSettings settings;

	public PackingEngine() {
		this(PlacementSupport.DEFAULT_POLICY, false, false,
				WholeOrderAssignmentStrategy.BALANCED, 0.85, false, 30_000L, 32, 32, 1);
	}

	@Autowired
	public PackingEngine(
			@Value("${packing.support.minimum-area-ratio:0.85}") double minimumAreaRatio,
			@Value("${packing.support.require-center:true}") boolean requireCenterSupport,
			@Value("${packing.support.maximum-overhang-mm:20}") int maximumOverhangMillimeters,
			@Value("${packing.support.maximum-overhang-ratio:0.05}") double maximumOverhangRatio,
			@Value("${packing.diagnostics.enabled:false}") boolean diagnosticsEnabled,
			@Value("${packing.block-beam-only:true}") boolean blockBeamOnly,
			@Value("${packing.assignment.strategy:fill-first}") String assignmentStrategy,
			@Value("${packing.assignment.target-fill-ratio:0.85}") double assignmentTargetFillRatio,
			@Value("${packing.tail-compaction.enabled:true}") boolean tailCompactionEnabled,
			@Value("${packing.tail-compaction.search-millis:60000}") long tailCompactionSearchMillis,
			@Value("${packing.tail-compaction.beam-width:32}") int tailCompactionBeamWidth,
			@Value("${packing.tail-compaction.branching:32}") int tailCompactionBranching,
			@Value("${packing.whole-order.parallelism:1}") int wholeOrderParallelism,
			@Value("${packing.whole-order.candidate-probe-enabled:false}") boolean candidateProbeEnabled,
			@Value("${packing.whole-order.candidate-probe-window:8}") int candidateProbeWindow,
			@Value("${packing.whole-order.candidate-probe-states:8}") int candidateProbeStates) {
		this(new PlacementSupport.Policy(minimumAreaRatio, requireCenterSupport,
				maximumOverhangMillimeters, maximumOverhangRatio), diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.parse(assignmentStrategy), assignmentTargetFillRatio,
				tailCompactionEnabled, tailCompactionSearchMillis,
				tailCompactionBeamWidth, tailCompactionBranching, wholeOrderParallelism,
				candidateProbeEnabled, candidateProbeWindow, candidateProbeStates);
	}

	public PackingEngine(PlacementSupport.Policy supportPolicy) {
		this(supportPolicy, false, false, WholeOrderAssignmentStrategy.BALANCED, 0.85,
				false, 30_000L, 32, 32, 1);
	}

	public PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled) {
		this(supportPolicy, diagnosticsEnabled, false, WholeOrderAssignmentStrategy.BALANCED, 0.85,
				false, 30_000L, 32, 32, 1);
	}

	public PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly) {
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.BALANCED, 0.85, false, 30_000L, 32, 32, 1);
	}

	public PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio) {
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly, assignmentStrategy, assignmentTargetFillRatio,
				false, 30_000L, 32, 32, 1);
	}

	public PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio,
			boolean tailCompactionEnabled, long tailCompactionSearchMillis,
			int tailCompactionBeamWidth, int tailCompactionBranching, int wholeOrderParallelism) {
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly, assignmentStrategy, assignmentTargetFillRatio,
				tailCompactionEnabled, tailCompactionSearchMillis, tailCompactionBeamWidth,
				tailCompactionBranching, wholeOrderParallelism, false,
				DEFAULT_CANDIDATE_PROBE_WINDOW, DEFAULT_CANDIDATE_PROBE_STATES);
	}

	public PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio,
			boolean tailCompactionEnabled, long tailCompactionSearchMillis,
			int tailCompactionBeamWidth, int tailCompactionBranching, int wholeOrderParallelism,
			boolean candidateProbeEnabled, int candidateProbeWindow, int candidateProbeStates) {
		this(supportPolicy, new PackingEngineSettings(diagnosticsEnabled, blockBeamOnly,
				assignmentStrategy, assignmentTargetFillRatio, tailCompactionEnabled,
				tailCompactionSearchMillis, tailCompactionBeamWidth, tailCompactionBranching,
				wholeOrderParallelism, candidateProbeEnabled, candidateProbeWindow, candidateProbeStates));
	}

	private PackingEngine(PlacementSupport.Policy supportPolicy, PackingEngineSettings settings) {
		this.settings = settings;
		this.wholeOrderCoordinator = new WholeOrderPackingCoordinator(supportPolicy, settings);
		this.blockBeamStrategy = new BlockBeamPackingStrategy(supportPolicy);
		this.legacyStrategy = new LegacyContainerPackingStrategy(supportPolicy, settings.diagnosticsEnabled());
	}

	public PackagerResult pack(PackingPlan plan) {
		PackingOutcome outcome = packOutcome(plan);
		return outcome.targetAchieved() ? outcome.result() : null;
	}

	public PackingOutcome packOutcome(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return new PackingOutcome(plan, null, false, false,
					totalContainerCount(plan.containerItems()));
		}
		int targetContainerCount = totalContainerCount(plan.containerItems());

		// Use the same production path for every target count, including one
		// container: fill-first assignment, Balanced Block Beam, original/swapped
		// orientations and first complete solution. This avoids the four-profile
		// quality search, gap filling and LNS used by the standalone flat-search
		// mode.
		PackagerResult targetResult = wholeOrderCoordinator.pack(
				plan, false, targetContainerCount, this::packFlat);
		if (targetResult != null && targetResult.isSuccess()) {
			return new PackingOutcome(plan, targetResult, true, false, targetContainerCount);
		}

		PackingPlan fallbackPlan = AutomaticContainerFallback.add40Hq(plan);
		System.out.println("packing-service automatic-fallback start targetContainers="
				+ targetContainerCount + " fallbackContainers=" + (targetContainerCount + 1)
				+ " addedContainerId="
				+ fallbackPlan.requestedContainers().get(fallbackPlan.requestedContainers().size() - 1).id());
		PackagerResult fallbackResult = wholeOrderCoordinator.pack(
				fallbackPlan, true, targetContainerCount, this::packFlat);
		boolean achieved = fallbackResult != null && fallbackResult.isSuccess()
				&& fallbackResult.size() <= targetContainerCount;
		System.out.println("packing-service automatic-fallback success=" + achieved
				+ " baselineSuccess=" + (fallbackResult != null && fallbackResult.isSuccess())
				+ " finalContainers=" + (fallbackResult == null ? 0 : fallbackResult.size())
				+ " targetContainers=" + targetContainerCount);
		return new PackingOutcome(fallbackPlan, fallbackResult, achieved, true, targetContainerCount);
	}

	private PackagerResult packFlat(PackingPlan plan, long deadline, PackingLogContext context) {
		PackagerResult best = null;
		best = better(best, packOrientation(plan, false, deadline, context));
		if (best != null && best.isSuccess() && best.size() == 1) {
			return best;
		}
		best = better(best, packOrientation(plan, true, deadline, context));
		return best;
	}

	private PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth, long deadline,
			PackingLogContext context) {
		List<ContainerItem> containers = swapLengthWidth
				? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult blockBeam = blockBeamStrategy.pack(plan, containers,
				"BlockBeam" + (swapLengthWidth ? "-SWAPPED" : ""), deadline, context);
		if (blockBeam != null && blockBeam.isSuccess()) {
			return blockBeam;
		}
		if (settings.blockBeamOnly()) {
			return null;
		}
		return legacyStrategy.pack(plan, containers, swapLengthWidth, deadline, context);
	}

	private static List<ContainerItem> swappedContainers(List<ContainerItem> source) {
		return source.stream().map(item -> {
			Container original = item.getContainer();
			Container swapped = Container.newBuilder()
					.withId(original.getId())
					.withDescription(original.getDescription() + "-SWAPPED")
					.withSize(original.getDy(), original.getDx(), original.getDz())
					.withEmptyWeight(original.getEmptyWeight())
					.withMaxLoadWeight(original.getMaxLoadWeight())
					.build();
			return new ContainerItem(swapped, item.getCount());
		}).toList();
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) return current;
		if (current == null || candidate.size() < current.size()) return candidate;
		if (candidate.size() == current.size()
				&& totalContainerCapacity(candidate) < totalContainerCapacity(current)) return candidate;
		if (candidate.size() == current.size()
				&& totalLoadVolume(candidate) > totalLoadVolume(current)) return candidate;
		if (candidate.size() == current.size() && totalLoadVolume(candidate) == totalLoadVolume(current)
				&& DoorSideRuleSupport.score(candidate).isBetterThan(DoorSideRuleSupport.score(current))) {
			return candidate;
		}
		return current;
	}

	private static int totalContainerCount(List<ContainerItem> containerItems) {
		int count = 0;
		for (ContainerItem item : containerItems) count += item.getCount();
		return count;
	}

	private static long totalLoadVolume(PackagerResult result) {
		return result.getContainers().stream().mapToLong(c -> c.getLoadVolume()).sum();
	}

	private static long totalContainerCapacity(PackagerResult result) {
		return result.getContainers().stream().mapToLong(Container::getMaxLoadVolume).sum();
	}
}
