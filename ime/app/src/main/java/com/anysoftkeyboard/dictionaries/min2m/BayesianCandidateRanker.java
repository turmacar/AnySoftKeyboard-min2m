package com.anysoftkeyboard.dictionaries.min2m;

import androidx.annotation.NonNull;

/**
 * Combines vocabulary frequency prior with spatial likelihood to produce a final candidate score.
 *
 * <p>score(word) = α × log(frequency / maxFreq) + β × spatialLogP
 *
 * <p>Higher scores are better. The α/β weights control the balance between "common word" bias and
 * "matches what was typed" bias. Spatial log-likelihood is NOT normalized per character - the raw
 * sum is the correct Bayesian log-likelihood. More characters = more evidence = properly stronger
 * spatial signal for same-length matches vs shorter edit-distance candidates.
 */
public class BayesianCandidateRanker {
  /**
   * Weight for vocabulary frequency prior (log-scaled). Reduced from 1.0 to prevent ultra-common
   * short words (we, I, a, the) from dominating spatial evidence on compact keyboards where keys
   * are narrow and spatial scoring is critical for disambiguation.
   */
  private float mAlpha = 0.5f;

  /**
   * Weight for spatial likelihood. Increased from 1.0 to give spatial evidence more influence on
   * compact/1D keyboards where adjacent keys are easily confused.
   */
  private float mBeta = 1.5f;

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
   * @param spatialLogP the spatial log-likelihood from SpatialScorer (negative; higher = better).
   *     This is the raw sum of per-keystroke Gaussian log-likelihoods, NOT normalized per
   *     character. More matching characters = more spatial evidence = higher score.
   * @param candidateLength unused (kept for API compatibility)
   * @return combined score (higher is better)
   */
  public float score(int frequency, float spatialLogP, int candidateLength) {
    // logPrior = ln(frequency / maxFrequency)   [range: ~−15 to 0]
    float logFreqPrior = (float) Math.log((double) frequency / mMaxFrequency);
    // score = α × logPrior + β × spatialLogP
    // No per-character normalization: the raw spatial sum IS the correct
    // Bayesian log-likelihood P(touches|word). Dividing by N inflates scores
    // for shorter words, causing "we" to beat "see" when 3 characters were typed.
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
    // Linear mapping from score range [-60, 0] to int range [1, MAX_VALUE/2 - 1].
    // Wide range to accommodate long words where the HMM accumulates per-character
    // spatial penalties. A 14-char word with moderate spatial distance per char
    // can score -20 to -40 total (spatial + frequency combined).
    // Scores below -60 clamp to 1, scores above 0 clamp to MAX_VALUE/2 - 1.
    double clamped = Math.max(-60.0, Math.min(0.0, score));
    double normalized = (clamped + 60.0) / 60.0;
    return 1 + (int) (normalized * (Integer.MAX_VALUE / 2 - 2));
  }
}
