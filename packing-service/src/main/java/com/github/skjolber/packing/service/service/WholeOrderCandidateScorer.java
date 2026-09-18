package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;

/** Cheap geometry-aware ranking for whole-order assignments. */
final class WholeOrderCandidateScorer {

	List<WholeOrderAssignmentSolver.Candidate> rank(
			List<WholeOrderAssignmentSolver.Candidate> candidates, List<Container> containers) {
		return rank(candidates, containers, WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	List<WholeOrderAssignmentSolver.Candidate> rank(
			List<WholeOrderAssignmentSolver.Candidate> candidates, List<Container> containers,
			WholeOrderAssignmentStrategy strategy, double targetFillRatio) {
		List<WholeOrderAssignmentSolver.Candidate> ranked = new ArrayList<>(candidates.size());
		for (WholeOrderAssignmentSolver.Candidate candidate : candidates) {
			ranked.add(candidate.withScore(score(candidate, containers, strategy, targetFillRatio)));
		}
		ranked.sort(Comparator
				.comparingDouble((WholeOrderAssignmentSolver.Candidate candidate) -> candidate.score().total())
				.thenComparingInt(WholeOrderAssignmentSolver.Candidate::generationIndex));
		return List.copyOf(ranked);
	}

	Score score(WholeOrderAssignmentSolver.Candidate candidate, List<Container> containers) {
		return score(candidate, containers, WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	Score score(WholeOrderAssignmentSolver.Candidate candidate, List<Container> containers,
			WholeOrderAssignmentStrategy strategy, double targetFillRatio) {
		double worstRisk = 0.0;
		double totalRisk = 0.0;
		double totalFragmentation = 0.0;
		double totalFitRisk = 0.0;
		double totalRepeatRisk = 0.0;
		double[] fills = new double[containers.size()];
		int usedContainers = 0;
		List<Double> containerRisks = new ArrayList<>(containers.size());
		for (int i = 0; i < containers.size(); i++) {
			ContainerRisk risk = containerRisk(candidate.itemsByContainer().get(i), containers.get(i));
			containerRisks.add(risk.total());
			worstRisk = Math.max(worstRisk, risk.total());
			totalRisk += risk.total();
			totalFragmentation += risk.fragmentation();
			totalFitRisk += risk.fitRisk();
			totalRepeatRisk += risk.repeatRisk();
			fills[i] = risk.volumeFill();
			if (!candidate.itemsByContainer().get(i).isEmpty()) usedContainers++;
		}
		double count = Math.max(1, containers.size());
		double imbalance = standardDeviation(fills);
		// The hardest cabinet dominates whether an assignment succeeds. Average
		// difficulty and load imbalance break ties without hiding one bad cabinet.
		double fillFirstPenalty = fillFirstPenalty(fills, targetFillRatio);
		double total = strategy == WholeOrderAssignmentStrategy.FILL_FIRST
				? fillFirstPenalty * 150.0
						+ worstRisk * 100.0 + (totalRisk / count) * 10.0
				: worstRisk * 100.0 + (totalRisk / count) * 10.0 + imbalance * 50.0;
		return new Score(total, worstRisk, totalFragmentation / count,
				totalFitRisk / count, totalRepeatRisk / count, imbalance,
				usedContainers, fillFirstPenalty, List.copyOf(containerRisks));
	}

	private static double fillFirstPenalty(double[] fills, double targetFillRatio) {
		if (fills.length == 0) return 0.0;
		// The final physical cabinet is the overflow/tail cabinet. Reward high,
		// non-increasing fill in every cabinet before it and a small tail load.
		double penalty = fills[fills.length - 1] * 0.25;
		for (int i = 0; i < fills.length - 1; i++) {
			if (fills[i] == 0.0) {
				penalty += targetFillRatio + 2.0;
				continue;
			}
			penalty += Math.max(0.0, targetFillRatio - fills[i]);
			if (fills[i + 1] > fills[i]) penalty += (fills[i + 1] - fills[i]) * 2.0;
		}
		return penalty;
	}

	private static ContainerRisk containerRisk(List<BoxItem> items, Container container) {
		if (items.isEmpty()) {
			return new ContainerRisk(0.0, 0.0, 0.0, 0.0, 0.0);
		}
		int units = items.stream().mapToInt(BoxItem::getCount).sum();
		long volume = items.stream().mapToLong(BoxItem::getVolume).sum();
		double volumeFill = volume / (double) container.getMaxLoadVolume();
		int singletonTypes = 0;
		double weightedFitRisk = 0.0;
		double weightedRepeatRisk = 0.0;
		double weight = 0.0;
		for (BoxItem item : items) {
			if (item.getCount() <= 2) singletonTypes++;
			double itemWeight = Math.max(1.0, item.getVolume());
			weightedFitRisk += fitRisk(item, container) * itemWeight;
			weightedRepeatRisk += (1.0 / Math.sqrt(Math.max(1, item.getCount()))) * itemWeight;
			weight += itemWeight;
		}
		double singletonRatio = singletonTypes / (double) items.size();
		double typeDensity = items.size() / Math.sqrt(Math.max(1, units));
		double fragmentation = typeDensity + singletonRatio * 2.0;
		double fitRisk = weightedFitRisk / weight;
		double repeatRisk = weightedRepeatRisk / weight;
		double total = 4.0 * Math.pow(volumeFill, 3)
				+ 0.45 * fragmentation + 1.5 * fitRisk + 0.5 * repeatRisk;
		return new ContainerRisk(total, volumeFill, fragmentation, fitRisk, repeatRisk);
	}

	private static double fitRisk(BoxItem item, Container container) {
		double bestEase = -1.0;
		int fittingOrientations = 0;
		for (BoxStackValue value : item.getBox().getStackValues()) {
			if (!container.canLoad(value)) continue;
			fittingOrientations++;
			double slack = ((container.getLoadDx() - value.getDx()) / (double) container.getLoadDx()
					+ (container.getLoadDy() - value.getDy()) / (double) container.getLoadDy()
					+ (container.getLoadDz() - value.getDz()) / (double) container.getLoadDz()) / 3.0;
			long grid = (long) (container.getLoadDx() / value.getDx())
					* (container.getLoadDy() / value.getDy())
					* (container.getLoadDz() / value.getDz());
			double gridEase = Math.min(1.0, grid / 8.0);
			bestEase = Math.max(bestEase, 0.75 * slack + 0.25 * gridEase);
		}
		if (fittingOrientations == 0) return 10.0;
		double orientationPenalty = 0.15 / fittingOrientations;
		return Math.max(0.0, 1.0 - bestEase) + orientationPenalty;
	}

	private static double standardDeviation(double[] values) {
		if (values.length == 0) return 0.0;
		double mean = 0.0;
		for (double value : values) mean += value;
		mean /= values.length;
		double variance = 0.0;
		for (double value : values) {
			double delta = value - mean;
			variance += delta * delta;
		}
		return Math.sqrt(variance / values.length);
	}

	record Score(double total, double worstContainer, double fragmentation,
			double fitRisk, double repeatRisk, double imbalance, int usedContainers,
			double fillFirstPenalty, List<Double> containerRisks) {
		List<Integer> validationOrder() {
			List<Integer> order = new ArrayList<>(containerRisks.size());
			for (int i = 0; i < containerRisks.size(); i++) order.add(i);
			order.sort(Comparator.<Integer>comparingDouble(containerRisks::get).reversed()
					.thenComparingInt(Integer::intValue));
			return order;
		}

		String logFields() {
			return " score=" + rounded(total) + " worst=" + rounded(worstContainer)
					+ " fragmentation=" + rounded(fragmentation) + " fitRisk=" + rounded(fitRisk)
					+ " repeatRisk=" + rounded(repeatRisk) + " imbalance=" + rounded(imbalance)
					+ " usedContainers=" + usedContainers
					+ " fillFirstPenalty=" + rounded(fillFirstPenalty);
		}

		private static double rounded(double value) {
			return Math.round(value * 10_000.0) / 10_000.0;
		}
	}

	private record ContainerRisk(double total, double volumeFill,
			double fragmentation, double fitRisk, double repeatRisk) {
	}
}
