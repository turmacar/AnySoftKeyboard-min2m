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
   * @param candidateLength the candidate word length in codepoints
   * @return combined score (higher is better)
   */
  public float score(int frequency, float spatialLogP, int candidateLength) {
    // logPrior = ln(frequency / maxFrequency)   [range: ~−15 to 0]
    float logFreqPrior = (float) Math.log((double) frequency / mMaxFrequency);
    // Normalize spatial score per character so longer words don't get
    // penalized just for having more characters to evaluate.
    // spatialPerChar = spatialLogP / N   [range: ~−5 to 0 per char]
    float spatialPerChar = candidateLength > 0 ? spatialLogP / candidateLength : spatialLogP;
    // score = α × logPrior + β × spatialPerChar
    return mAlpha * logFreqPrior + mBeta * spatialPerChar;
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
    // Linear mapping from score range [-20, 0] to int range [1, MAX_VALUE/2 - 1].
    // Scores below -20 clamp to 1, scores above 0 clamp to MAX_VALUE/2 - 1.
    // This preserves the relative differences between scores, unlike sigmoid
    // which compresses the useful range (-5 to 0) into <1% of the output.
    //
    // priority = clamp((score + 20) / 20, 0, 1) × (MAX_VALUE/2 - 2) + 1
    double clamped = Math.max(-20.0, Math.min(0.0, score));
    double normalized = (clamped + 20.0) / 20.0;
    return 1 + (int) (normalized * (Integer.MAX_VALUE / 2 - 2));
  }
}
