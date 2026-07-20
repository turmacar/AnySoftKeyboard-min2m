package com.anysoftkeyboard.dictionaries.min2m;

import androidx.annotation.NonNull;

/**
 * Combines vocabulary frequency prior with spatial likelihood to produce a final candidate score.
 *
 * <p>score(word) = log(frequency / maxFreq) + blendedSpatialLogP
 *
 * <p>Uses pure Bayesian scoring (α=1, β=1): score = log P(word) + log P(touches|word).
 * Spatial discrimination comes from dual-sigma blending (normal + tight Gaussian)
 * rather than artificial weight distortion.
 *
 * <p>Dual-sigma blending (Minuum model):
 * <pre>
 *   blendedLogP = log((1 - tightWeight) * exp(normalLogP) + tightWeight * exp(tightLogP))
 * </pre>
 * When tightWeight = 0, pure normal sigma (forgiving).
 * When tightWeight = 1, pure tight sigma (precise).
 */
public class BayesianCandidateRanker {

  /**
   * Blend factor between normal and tight sigma scores (0.0 - 1.0).
   * Default 0.3: light blending of tight sigma for better discrimination
   * on compact keyboards without being overly punishing.
   * Minuum default was 0.0 but allowed user tuning.
   */
  private float mTightWeight = 0.3f;

  private int mMaxFrequency = 1;

  public void setMaxFrequency(int maxFrequency) {
    mMaxFrequency = Math.max(1, maxFrequency);
  }

  public int getMaxFrequency() {
    return mMaxFrequency;
  }

  public void setTightWeight(float tightWeight) {
    mTightWeight = Math.max(0f, Math.min(1f, tightWeight));
  }

  public float getTightWeight() {
    return mTightWeight;
  }

  /**
   * Computes the combined score for a candidate word with dual-sigma blending.
   *
   * <p>score = log(freq/maxFreq) + blendedSpatialLogP
   *
   * <p>The blended spatial score combines normal and tight sigma HMM evaluations:
   * blendedLogP = logSumExp((1-tightWeight)*normalLogP, tightWeight*tightLogP)
   *
   * @param frequency the word's corpus frequency from vocab.db
   * @param normalSpatialLogP spatial log-likelihood from HMM with normal sigma
   * @param tightSpatialLogP spatial log-likelihood from HMM with tight sigma
   * @param candidateLength unused (kept for API compatibility)
   * @return combined score (higher is better)
   */
  public float score(int frequency, float normalSpatialLogP, float tightSpatialLogP,
      int candidateLength) {
    float logFreqPrior = (float) Math.log((double) frequency / mMaxFrequency);

    // Dual-sigma blending in log-space:
    // blended = log((1-w) * exp(normalLogP) + w * exp(tightLogP))
    float blendedSpatialLogP;
    if (mTightWeight <= 0f) {
      blendedSpatialLogP = normalSpatialLogP;
    } else if (mTightWeight >= 1f) {
      blendedSpatialLogP = tightSpatialLogP;
    } else {
      float logNormalWeight = (float) Math.log(1.0 - mTightWeight);
      float logTightWeight = (float) Math.log(mTightWeight);
      float a = logNormalWeight + normalSpatialLogP;
      float b = logTightWeight + tightSpatialLogP;
      float max = Math.max(a, b);
      blendedSpatialLogP = max + (float) Math.log(Math.exp(a - max) + Math.exp(b - max));
    }

    // Pure Bayesian: log P(word) + log P(touches|word)
    return logFreqPrior + blendedSpatialLogP;
  }

  /**
   * Single-sigma scoring (backward compatibility for space-omission sub-scoring
   * where we don't want to double-evaluate).
   */
  public float score(int frequency, float spatialLogP, int candidateLength) {
    float logFreqPrior = (float) Math.log((double) frequency / mMaxFrequency);
    return logFreqPrior + spatialLogP;
  }

  /**
   * Scores a candidate using only frequency (no spatial data available).
   *
   * @param frequency the word's corpus frequency
   * @return frequency-only score
   */
  public float scoreFrequencyOnly(int frequency) {
    return (float) Math.log((double) frequency / mMaxFrequency);
  }

  /**
   * Converts a Bayesian score to an integer priority compatible with ASK's suggestion ranking
   * system (higher int = higher priority). Maps the score range to roughly [1, MAX_VALUE/2].
   *
   * <p>With pure Bayesian scoring (α=1, β=1), scores are log-probabilities:
   * - Perfect common word: ~0 (log(1) + log(1))
   * - Common word, moderate spatial: ~-5
   * - Rare word, good spatial: ~-15
   * - Rare word, poor spatial: ~-40
   *
   * @param score the Bayesian score from {@link #score} or {@link #scoreFrequencyOnly}
   * @return integer priority for insertion into the suggestion list
   */
  public static int toIntPriority(float score) {
    double clamped = Math.max(-60.0, Math.min(0.0, score));
    double normalized = (clamped + 60.0) / 60.0;
    return 1 + (int) (normalized * (Integer.MAX_VALUE / 2 - 2));
  }
}
