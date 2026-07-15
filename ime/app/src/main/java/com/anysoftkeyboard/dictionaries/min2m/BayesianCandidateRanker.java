package com.anysoftkeyboard.dictionaries.min2m;

import androidx.annotation.NonNull;

/**
 * Combines vocabulary frequency prior with spatial likelihood to produce a final candidate score.
 *
 * <p>score(word) = α × log(frequency / maxFreq) + β × spatialLogP
 *
 * <p>Higher scores are better. The α/β weights control the balance between "common word" bias and
 * "matches what was typed" bias.
 */
public class BayesianCandidateRanker {
  /** Weight for vocabulary frequency prior (log-scaled). */
  private float mAlpha = 1.0f;

  /** Weight for spatial likelihood. */
  private float mBeta = 1.0f;

  private int mMaxFrequency = 1;

  public void setMaxFrequency(int maxFrequency) {
    mMaxFrequency = Math.max(1, maxFrequency);
  }

  public void setWeights(float alpha, float beta) {
    mAlpha = alpha;
    mBeta = beta;
  }

  /**
   * Computes the combined score for a candidate word.
   *
   * @param frequency the word's corpus frequency from vocab.db
   * @param spatialLogP the spatial log-likelihood from SpatialScorer (negative; higher = better)
   * @return combined score (higher is better)
   */
  public float score(int frequency, float spatialLogP) {
    // logPrior = ln(frequency / maxFrequency)   [range: ~−15 to 0]
    float logFreqPrior = (float) Math.log((double) frequency / mMaxFrequency);
    // score = α × logPrior + β × logP(touches | word)
    return mAlpha * logFreqPrior + mBeta * spatialLogP;
  }

  /**
   * Scores a candidate using only frequency (no spatial data available).
   *
   * @param frequency the word's corpus frequency
   * @return frequency-only score
   */
  public float scoreFrequencyOnly(int frequency) {
    // score = α × ln(frequency / maxFrequency)
    return mAlpha * (float) Math.log((double) frequency / mMaxFrequency);
  }

  /**
   * Converts a Bayesian score to an integer priority compatible with ASK's suggestion ranking
   * system (higher int = higher priority). Maps the score range to roughly [1, MAX_VALUE/2].
   *
   * @param score the Bayesian score from {@link #score} or {@link #scoreFrequencyOnly}
   * @return integer priority for insertion into the suggestion list
   */
  public static int toIntPriority(float score) {
    // sigmoid(x) = 1 / (1 + e^(−(score + 10)))   [centered at score = −10]
    double normalized = 1.0 / (1.0 + Math.exp(-(score + 10.0)));
    // priority = 1 + sigmoid × (MAX_VALUE/2 − 2)   [maps to int range 1..MAX_VALUE/2−1]
    return 1 + (int) (normalized * (Integer.MAX_VALUE / 2 - 2));
  }
}
