package com.github.skjolber.packing.service.service.blockbeam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.service.rule.BottomRuleSupport;
import com.github.skjolber.packing.service.service.rule.DoorSideRuleSupport;
import com.github.skjolber.packing.service.service.rule.NoPressRuleSupport;
import com.github.skjolber.packing.service.service.PackingLogContext;
import com.github.skjolber.packing.service.service.PackingPlan;
import com.github.skjolber.packing.service.service.rule.PackingBusinessRules;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

/** Executes Block Beam profiles and the optional standalone repair stages. */
public final class BlockBeamPackingStrategy {
	private static final long SEARCH_MILLIS = 30_000L;
	private static final long QUICK_SEARCH_MILLIS = 2_000L;
	private static final int INTENSIVE_WIDTH = 128;
	private static final int INTENSIVE_BRANCHING = 64;
	private static final int INTENSIVE_PROFILES = 2;

	private final PlacementSupport.Policy supportPolicy;

	public BlockBeamPackingStrategy(PlacementSupport.Policy supportPolicy) {
		this.supportPolicy = supportPolicy;
	}

	public PackagerResult pack(PackingPlan plan, List<ContainerItem> containers,
			String label, long deadline, PackingLogContext context) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) return null;
		long started = System.nanoTime();
		long searchDeadline = System.currentTimeMillis() + SEARCH_MILLIS;
		if (deadline > 0L) searchDeadline = Math.min(searchDeadline, deadline);
		List<Attempt> quickAttempts = new ArrayList<>();
		PackagerResult result = null;
		BlockBeamSearchPackager.SearchProgress bestProgress = null;
		boolean wholeOrderValidation = context.searchId() != 0L;
		boolean allowLns = !wholeOrderValidation && !PackingBusinessRules.hasAny(plan);
		long explorationDeadline = allowLns
				? Math.max(System.currentTimeMillis(), searchDeadline - 8_000L)
				: searchDeadline;

		if (wholeOrderValidation) {
			BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
					64, 48, supportPolicy, BlockBeamSearchPackager.SearchProfile.BALANCED);
			BlockBeamSearchPackager.SearchSession session = packager.newSession(
					containers.get(0).getContainer(), plan.boxItems(),
					context.fields() + " effort=baseline profile=balanced");
			if (session != null) {
				BlockBeamSearchPackager.SearchProgress progress = session.advance(explorationDeadline);
				quickAttempts.add(new Attempt(
						BlockBeamSearchPackager.SearchProfile.BALANCED, progress, session.bestPartial()));
				bestProgress = progress;
				if (progress.complete()) result = progress.result();
			}
		}

		if (!wholeOrderValidation) {
			for (BlockBeamSearchPackager.SearchProfile profile : BlockBeamSearchPackager.SearchProfile.values()) {
				if (result != null || System.currentTimeMillis() >= explorationDeadline) break;
				BlockBeamSearchPackager packager = new BlockBeamSearchPackager(64, 48, supportPolicy, profile);
				BlockBeamSearchPackager.SearchSession session = packager.newSession(
						containers.get(0).getContainer(), plan.boxItems(),
						context.fields() + " effort=quick profile=" + profile.propertyValue());
				if (session == null) continue;
				long quickDeadline = Math.min(explorationDeadline,
						System.currentTimeMillis() + QUICK_SEARCH_MILLIS);
				BlockBeamSearchPackager.SearchProgress progress = session.advance(quickDeadline);
				quickAttempts.add(new Attempt(profile, progress, session.bestPartial()));
				bestProgress = betterProgress(bestProgress, progress);
				if (progress.complete()) {
					result = progress.result();
					break;
				}
			}
		}

		if (!wholeOrderValidation && result == null && System.currentTimeMillis() < explorationDeadline) {
			quickAttempts.sort(Comparator
					.comparingDouble((Attempt attempt) -> attempt.progress().volumeCompletionRatio()).reversed()
					.thenComparing(Comparator.comparingDouble((Attempt attempt) ->
							attempt.progress().completionRatio()).reversed())
					.thenComparing(Comparator.comparingInt((Attempt attempt) ->
							attempt.progress().eliteStates()).reversed()));
			List<Attempt> intensiveAttempts = new ArrayList<>();
			quickAttempts.stream()
					.filter(attempt -> attempt.profile() == BlockBeamSearchPackager.SearchProfile.BALANCED)
					.findFirst().ifPresent(intensiveAttempts::add);
			for (Attempt attempt : quickAttempts) {
				if (intensiveAttempts.size() >= INTENSIVE_PROFILES) break;
				if (!intensiveAttempts.contains(attempt)) intensiveAttempts.add(attempt);
			}
			int intensiveCount = intensiveAttempts.size();
			for (int index = 0; index < intensiveCount && System.currentTimeMillis() < explorationDeadline; index++) {
				long remaining = explorationDeadline - System.currentTimeMillis();
				int remainingProfiles = intensiveCount - index;
				long share = index == 0 && remainingProfiles > 1
						? Math.max(1L, Math.round(remaining * 0.7))
						: Math.max(1L, remaining / remainingProfiles);
				BlockBeamSearchPackager.SearchProfile profile = intensiveAttempts.get(index).profile();
				BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
						INTENSIVE_WIDTH, INTENSIVE_BRANCHING, supportPolicy, profile);
				BlockBeamSearchPackager.SearchSession session = packager.newSession(
						containers.get(0).getContainer(), plan.boxItems(),
						context.fields() + " effort=intensive profile=" + profile.propertyValue());
				if (session == null) continue;
				BlockBeamSearchPackager.SearchProgress progress = session.advance(
						Math.min(explorationDeadline, System.currentTimeMillis() + share));
				quickAttempts.add(new Attempt(profile, progress, session.bestPartial()));
				bestProgress = betterProgress(bestProgress, progress);
				if (progress.complete()) {
					result = progress.result();
					break;
				}
			}
		}

		if (result == null && allowLns && System.currentTimeMillis() < searchDeadline) {
			BlockBeamSearchPackager.PartialSolution partial = quickAttempts.stream()
					.map(Attempt::partial)
					.filter(Objects::nonNull)
					.max(Comparator.comparingDouble(
							BlockBeamSearchPackager.PartialSolution::volumeCompletionRatio))
					.orElse(null);
			if (partial != null) {
				result = new ExtremePointGapFiller(supportPolicy).fill(partial, searchDeadline, context.fields());
				if (result == null && System.currentTimeMillis() < searchDeadline) {
					result = new BlockBeamLargeNeighborhoodSearch(supportPolicy)
							.repair(partial, searchDeadline, context.fields());
				}
			}
		}

		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		if (result == null || !result.isSuccess()) {
			System.out.println("packing-service " + label + context.fields()
					+ " success=false elapsedMs=" + elapsedMillis
					+ " reason=" + (System.currentTimeMillis() >= searchDeadline ? "deadline" : "search-exhausted")
					+ " quickProfiles=" + quickAttempts.size()
					+ " completion=" + rounded(bestProgress == null ? 0.0 : bestProgress.completionRatio())
					+ " volumeCompletion=" + rounded(bestProgress == null ? 0.0
							: bestProgress.volumeCompletionRatio()));
			return null;
		}
		PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
		boolean valid = support.valid() && BottomRuleSupport.isValid(result)
				&& NoPressRuleSupport.isValid(result);
		if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
			DoorSideRuleSupport.mirrorToDoorSide(result);
			valid = DoorSideRuleSupport.isValid(result);
		}
		System.out.println("packing-service " + label + context.fields() + " success=" + valid
				+ " placements=" + result.get(0).getStack().size() + " elapsedMs=" + elapsedMillis
				+ (support.valid() ? "" : " rejectedBox=" + support.boxId()
						+ " reason=" + support.reason() + " ratio=" + support.supportRatio()));
		return valid ? result : null;
	}

	private static BlockBeamSearchPackager.SearchProgress betterProgress(
			BlockBeamSearchPackager.SearchProgress current,
			BlockBeamSearchPackager.SearchProgress candidate) {
		if (current == null) return candidate;
		int volume = Double.compare(candidate.volumeCompletionRatio(), current.volumeCompletionRatio());
		if (volume != 0) return volume > 0 ? candidate : current;
		int units = Double.compare(candidate.completionRatio(), current.completionRatio());
		if (units != 0) return units > 0 ? candidate : current;
		return candidate.eliteStates() > current.eliteStates() ? candidate : current;
	}

	private static int totalContainerCount(List<ContainerItem> containerItems) {
		int count = 0;
		for (ContainerItem item : containerItems) count += item.getCount();
		return count;
	}

	private static double rounded(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private record Attempt(BlockBeamSearchPackager.SearchProfile profile,
			BlockBeamSearchPackager.SearchProgress progress,
			BlockBeamSearchPackager.PartialSolution partial) {
	}
}
