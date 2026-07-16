package com.anysoftkeyboard.dictionaries.min2m;

import androidx.annotation.NonNull;

/**
 * Character-level Hidden Markov Model for spatial disambiguation.
 * Computes P(touches | word) using the forward algorithm over an HMM where:
 *
 * <p>Hidden states per character position:
 * <ul>
 *   <li>MATCH - normal character typed (emission = Gaussian distance)</li>
 *   <li>INSERTION - extra accidental touch (consumes a touch, no character advance)</li>
 * </ul>
 *
 * <p>Deletions (missed keys) are handled via skip transitions that advance
 * the character pointer without consuming a touch.
 *
 * <p>The forward algorithm sums over all possible alignments between the
 * touch sequence and the candidate word, naturally handling any combination
 * of substitutions, insertions, and deletions without brute-force enumeration.
 *
 * <p>Architecture modeled on Minuum's native wsHMMEvaluate with
 * WS_HMM_CHARACTER, WS_HMM_ADDITION, and WS_HMM_OMITTABLE_CHARACTER states.
 */
public class HMMScorer {
  private static final String TAG = "HMMScorer";

  /** Reference to SpatialScorer for key centers and sigma. */
  @NonNull private final SpatialScorer mSpatialScorer;

  // --- Transition probabilities (log-space) ---

  /**
   * Log-probability of an insertion (accidental extra touch).
   * A touch is consumed but no character is advanced.
   * Minuum's param: related to WS_HMM_ADDITION state transition.
   */
  private float mLogPInsertion = -4.0f; // ~1.8% chance per position

  /**
   * Log-probability of a deletion (missed key).
   * A character is skipped without consuming a touch.
   * Minuum's param: related to WS_HMM_OMITTABLE_CHARACTER transition.
   */
  private float mLogPDeletion = -4.0f; // ~1.8% chance per position

  /**
   * Log-probability of skipping an apostrophe (contraction).
   * Set to 0.0 (truly free/neutral): the apostrophe is invisible to the HMM.
   * Users type "im" for "I'm", "dont" for "don't" — the apostrophe is an
   * expected omission. A non-zero value (even -0.1) gives apostrophe words
   * a slight scoring advantage over non-apostrophe words of the same spatial
   * quality, causing e.g. "Liechtenstein's" to beat "Historically".
   */
  private static final float LOG_P_APOSTROPHE_SKIP = 0.0f;

  /**
   * Log-probability of normal match transition.
   * Must be log(1 - p_insertion - p_deletion) but since we're in log-space
   * and these are small probabilities, this is close to 0.
   */
  private float mLogPMatch = -0.037f; // ~log(1 - 0.018 - 0.018) = log(0.964)

  // --- Pre-allocated forward matrix to avoid GC ---
  // Dimensions: [touchCount+1][candidateLen+1]
  // Value: log-probability of generating touches[0..t-1] from candidate[0..c-1]
  private float[][] mForward;
  private int mForwardRows;
  private int mForwardCols;

  public HMMScorer(@NonNull SpatialScorer spatialScorer) {
    mSpatialScorer = spatialScorer;
    mForward = new float[16][16]; // initial size, grown as needed
    mForwardRows = 16;
    mForwardCols = 16;
  }

  /**
   * Set HMM transition probabilities.
   *
   * @param pInsertion probability of an accidental extra touch (0-1)
   * @param pDeletion probability of a missed key (0-1)
   */
  public void setTransitionProbabilities(float pInsertion, float pDeletion) {
    mLogPInsertion = (float) Math.log(Math.max(1e-10, pInsertion));
    mLogPDeletion = (float) Math.log(Math.max(1e-10, pDeletion));
    float pMatch = 1.0f - pInsertion - pDeletion;
    mLogPMatch = (float) Math.log(Math.max(1e-10, pMatch));
  }

  /**
   * Scores how well a touch sequence matches a candidate word using the
   * forward algorithm over the character-level HMM.
   *
   * <p>Returns log P(touches | word), a negative value where higher = better.
   * Comparable to SpatialScorer.scoreWord() output.
   *
   * @param candidate the candidate word (lowercase)
   * @param touchXs per-keystroke X coordinates
   * @param touchYs per-keystroke Y coordinates
   * @param touchCount number of touch positions
   * @return log-likelihood (negative; higher = better match)
   */
  public float scoreWord(
      @NonNull String candidate, float[] touchXs, float[] touchYs, int touchCount) {
    if (!mSpatialScorer.hasKeyboard() || touchCount == 0) return 0f;

    // Extract candidate code points
    int candidateLen = candidate.codePointCount(0, candidate.length());
    if (candidateLen == 0) return 0f;

    // Reject candidates with length difference > 2 (too many edits)
    int lenDiff = Math.abs(candidateLen - touchCount);
    if (lenDiff > 2) {
      return -2.0f * lenDiff;
    }

    int[] codePoints = new int[candidateLen];
    int cpIdx = 0;
    for (int i = 0; i < candidate.length(); ) {
      codePoints[cpIdx++] = Character.toLowerCase(Character.codePointAt(candidate, i));
      i += Character.charCount(codePoints[cpIdx - 1]);
    }

    // Pre-compute emission log-probabilities: logEmit[t][c]
    // = log P(touch_t | character_c) = -d^2 / (2 * sigma^2)
    // Plus a uniform floor for touches with unknown key positions.
    float sigmaSquared = mSpatialScorer.getSigmaSquared();
    int T = touchCount;
    int C = candidateLen;

    // Ensure forward matrix is large enough
    ensureForwardSize(T + 1, C + 1);

    // --- Forward algorithm ---
    // forward[t][c] = log P(touches[0..t-1] aligned to candidate[0..c-1])
    //
    // Three transitions into forward[t][c]:
    // 1. MATCH: forward[t-1][c-1] + logPMatch + logEmit(touch_t-1, char_c-1)
    //    - consume one touch, advance one character
    // 2. INSERTION: forward[t-1][c] + logPInsertion + logEmitInsertion
    //    - consume one touch (accidental), stay at same character
    // 3. DELETION: forward[t][c-1] + logPDeletion
    //    - skip a character without consuming a touch

    float NEG_INF = Float.NEGATIVE_INFINITY;

    // Initialize
    for (int t = 0; t <= T; t++) {
      for (int c = 0; c <= C; c++) {
        mForward[t][c] = NEG_INF;
      }
    }
    mForward[0][0] = 0f; // start: no touches consumed, no characters matched

    // Allow leading deletions (candidate starts with skipped chars)
    for (int c = 1; c <= C; c++) {
      float deletePenalty = (codePoints[c - 1] == '\'')
          ? LOG_P_APOSTROPHE_SKIP : mLogPDeletion;
      mForward[0][c] = mForward[0][c - 1] + deletePenalty;
    }

    // Emission probability for an insertion (accidental touch).
    // This is a uniform distribution over the keyboard - any key is equally
    // likely for an accidental tap. log(1/26) ~= -3.26
    float logEmitInsertion = -3.26f;

    // Fill forward matrix
    for (int t = 1; t <= T; t++) {
      float tx = touchXs[t - 1];
      float ty = touchYs[t - 1];

      // Allow leading insertions (extra touches before first char)
      // forward[t][0] = forward[t-1][0] + logPInsertion + logEmitInsertion
      mForward[t][0] = mForward[t - 1][0] + mLogPInsertion + logEmitInsertion;

      for (int c = 1; c <= C; c++) {
        // Emission probability for matching touch t-1 to character c-1
        float logEmit = computeLogEmission(tx, ty, codePoints[c - 1], sigmaSquared);

        // Transition 1: MATCH (consume touch + advance character)
        float matchScore = mForward[t - 1][c - 1] + mLogPMatch + logEmit;

        // Transition 2: INSERTION (consume touch, stay at same character)
        float insertScore = mForward[t - 1][c] + mLogPInsertion + logEmitInsertion;

        // Transition 3: DELETION (skip character, no touch consumed)
        // Apostrophes are nearly free to skip - users type "im" for "I'm",
        // "dont" for "don't". Other characters use the standard penalty.
        float deletePenalty = (codePoints[c - 1] == '\'')
            ? LOG_P_APOSTROPHE_SKIP : mLogPDeletion;
        float deleteScore = mForward[t][c - 1] + deletePenalty;

        // Log-sum-exp of the three paths
        mForward[t][c] = logSumExp3(matchScore, insertScore, deleteScore);
      }
    }

    // Final: allow trailing deletions (candidate ends with skipped chars)
    // The answer is the best of forward[T][c] + deletion penalties for
    // remaining chars. But since we already allow deletions at each step,
    // the forward[T][C] cell already accounts for this.
    return mForward[T][C];
  }

  /**
   * Computes log emission probability: log P(touch | character).
   * Uses Gaussian model: log P = -d^2 / (2 * sigma^2) - log(2*pi*sigma^2)/2
   * The normalization constant is the same for all candidates so we drop it.
   */
  private float computeLogEmission(float touchX, float touchY, int charCode, float sigmaSquared) {
    float[] center = mSpatialScorer.getKeyCenter(charCode);
    if (center == null || touchX < 0) {
      // Unknown character position (e.g., apostrophe) - use flat prior
      return -3.26f; // log(1/26), treat as uniform
    }
    float dx = touchX - center[0];
    float dy = touchY - center[1];
    float distSq = dx * dx + dy * dy;
    return -distSq / (2f * sigmaSquared);
  }

  /**
   * Computes log(exp(a) + exp(b) + exp(c)) in a numerically stable way.
   * Used for summing probabilities in log-space (forward algorithm).
   */
  private static float logSumExp3(float a, float b, float c) {
    float max = Math.max(a, Math.max(b, c));
    if (max == Float.NEGATIVE_INFINITY) return Float.NEGATIVE_INFINITY;
    return max + (float) Math.log(
        Math.exp(a - max) + Math.exp(b - max) + Math.exp(c - max));
  }

  /** Ensures the forward matrix is at least rows x cols. */
  private void ensureForwardSize(int rows, int cols) {
    if (rows > mForwardRows || cols > mForwardCols) {
      mForwardRows = Math.max(rows, mForwardRows * 2);
      mForwardCols = Math.max(cols, mForwardCols * 2);
      mForward = new float[mForwardRows][mForwardCols];
    }
  }
}
