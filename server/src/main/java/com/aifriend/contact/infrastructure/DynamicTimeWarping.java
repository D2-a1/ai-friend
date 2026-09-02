package com.aifriend.contact.infrastructure;

import java.util.Arrays;

/**
 * 对两段 MFCC 时序计算带 Sakoe-Chiba 窗口的归一化 DTW 距离。
 */
final class DynamicTimeWarping {

    double distance(float[][] left, float[][] right, double windowRatio) {
        validate(left, right);
        int leftLength = left.length;
        int rightLength = right.length;
        int window = Math.max(Math.abs(leftLength - rightLength),
                (int) Math.ceil(Math.max(leftLength, rightLength) * windowRatio));
        double[] previousCosts = new double[rightLength + 1];
        double[] currentCosts = new double[rightLength + 1];
        int[] previousSteps = new int[rightLength + 1];
        int[] currentSteps = new int[rightLength + 1];
        Arrays.fill(previousCosts, Double.POSITIVE_INFINITY);
        previousCosts[0] = 0.0D;

        for (int leftIndex = 1; leftIndex <= leftLength; leftIndex++) {
            Arrays.fill(currentCosts, Double.POSITIVE_INFINITY);
            Arrays.fill(currentSteps, 0);
            int rightStart = Math.max(1, leftIndex - window);
            int rightEnd = Math.min(rightLength, leftIndex + window);
            for (int rightIndex = rightStart;
                    rightIndex <= rightEnd;
                    rightIndex++) {
                Predecessor predecessor = minimumPredecessor(
                        previousCosts[rightIndex], previousSteps[rightIndex],
                        currentCosts[rightIndex - 1], currentSteps[rightIndex - 1],
                        previousCosts[rightIndex - 1], previousSteps[rightIndex - 1]);
                if (Double.isFinite(predecessor.cost())) {
                    currentCosts[rightIndex] = predecessor.cost()
                            + frameDistance(left[leftIndex - 1], right[rightIndex - 1]);
                    currentSteps[rightIndex] = predecessor.steps() + 1;
                }
            }
            double[] costSwap = previousCosts;
            previousCosts = currentCosts;
            currentCosts = costSwap;
            int[] stepSwap = previousSteps;
            previousSteps = currentSteps;
            currentSteps = stepSwap;
        }
        if (!Double.isFinite(previousCosts[rightLength])
                || previousSteps[rightLength] < 1) {
            throw new IllegalArgumentException("DTW_PATH_UNAVAILABLE");
        }
        return previousCosts[rightLength] / previousSteps[rightLength];
    }

    private void validate(float[][] left, float[][] right) {
        if (left == null || right == null
                || left.length == 0 || right.length == 0
                || left[0].length == 0
                || left[0].length != right[0].length) {
            throw new IllegalArgumentException("FEATURE_SHAPE_INVALID");
        }
    }

    private Predecessor minimumPredecessor(
            double upperCost,
            int upperSteps,
            double leftCost,
            int leftSteps,
            double diagonalCost,
            int diagonalSteps) {
        double minimumCost = diagonalCost;
        int minimumSteps = diagonalSteps;
        if (upperCost < minimumCost) {
            minimumCost = upperCost;
            minimumSteps = upperSteps;
        }
        if (leftCost < minimumCost) {
            minimumCost = leftCost;
            minimumSteps = leftSteps;
        }
        return new Predecessor(minimumCost, minimumSteps);
    }

    private double frameDistance(float[] left, float[] right) {
        double squaredDistance = 0.0D;
        for (int index = 0; index < left.length; index++) {
            double difference = left[index] - right[index];
            squaredDistance += difference * difference;
        }
        return Math.sqrt(squaredDistance / left.length);
    }

    private record Predecessor(double cost, int steps) {
    }
}
