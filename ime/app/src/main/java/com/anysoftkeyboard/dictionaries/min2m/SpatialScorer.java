package com.anysoftkeyboard.dictionaries.min2m;

import androidx.annotation.NonNull;
import com.anysoftkeyboard.keyboards.Keyboard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes spatial likelihood scores: P(touches | word). For each candidate word, measures how well
 * the actual touch positions match the expected key positions on the keyboard.
 *
 * <p>Uses a Gaussian model: each touch contributes log P = -distance² / (2σ²), summed across all
 * character positions.
 */
public class SpatialScorer {
  /** Map from lowercase character code to its key center (x, y). */
  private final Map<Integer, float[]> mKeyCenters = new HashMap<>();

  /** Gaussian width parameter squared. Controls tolerance for off-center touches. */
  private float mSigmaSquared = 1f;

  /** Average key width, used as fallback sigma and for normalization. */
  private float mAvgKeyWidth = 1f;

  /**
   * Minimum σ in pixels, based on physical finger size (~7-10mm ≈ 40-60px on
   * a typical 400-440 dpi phone screen). Prevents σ from being too narrow on
   * compact/1D keyboards where keys are much smaller than a fingertip.
   * Without this floor, being off by 1 key on a 28px-wide 1D keyboard gives
   * a devastating −2.0 per-character penalty (σ=14px), making it impossible
   * for "well" to beat "zoo" when touches land 1 key off.
   */
  private static final float MIN_SIGMA_PX = 48f;

  private boolean mHasKeyboard = false;

  /**
   * Registers the current keyboard geometry. Call this when the keyboard changes.
   *
   * @param keys the list of keys from the current keyboard
   */
  public void registerKeyboard(@NonNull List<Keyboard.Key> keys) {
    mKeyCenters.clear();
    float totalWidth = 0;
    int letterKeyCount = 0;

    for (Keyboard.Key key : keys) {
      int code = key.getPrimaryCode();
      if (code <= 0) continue;

      // center = (key.x + key.width/2, key.y + key.height/2)
      float cx = key.x + key.width / 2f;
      float cy = key.y + key.height / 2f;

      // Store lowercase mapping so candidate lookup is case-insensitive
      int lowerCode = Character.toLowerCase(code);
      if (!mKeyCenters.containsKey(lowerCode)) {
        mKeyCenters.put(lowerCode, new float[] {cx, cy});
      }

      // Track letter keys for average width calculation
      if (Character.isLetter(code)) {
        totalWidth += key.width;
        letterKeyCount++;
      }
    }

    if (letterKeyCount > 0) {
      mAvgKeyWidth = totalWidth / letterKeyCount;
      // σ = max(avgKeyWidth / 2, MIN_SIGMA_PX)
      // On a full 2D keyboard (key width ~100px), σ ≈ 50px (natural).
      // On a 1D keyboard (key width ~28px), σ = 48px (floor), tolerating
      // touches up to ~1.7 keys off before the Gaussian penalty exceeds −1.
      float sigma = Math.max(mAvgKeyWidth * 0.5f, MIN_SIGMA_PX);
      mSigmaSquared = sigma * sigma;
    }

    mHasKeyboard = !mKeyCenters.isEmpty();
  }

  public boolean hasKeyboard() {
    return mHasKeyboard;
  }

  /**
   * Scores how well the touch positions match the expected key positions for a candidate word.
   *
   * @param candidate the candidate word (lowercase)
   * @param touchXs per-keystroke X coordinates from WordComposer
   * @param touchYs per-keystroke Y coordinates from WordComposer
   * @param touchCount number of touch positions
   * @return log-likelihood score (negative; higher = better match). Returns {@link
   *     Float#NEGATIVE_INFINITY} if the word can't be scored.
   */
  public float scoreWord(
      @NonNull String candidate, float[] touchXs, float[] touchYs, int touchCount) {
    if (!mHasKeyboard || touchCount == 0) return 0f;

    int candidateLen = candidate.codePointCount(0, candidate.length());
    if (candidateLen == touchCount) {
      return scoreAligned(candidate, touchXs, touchYs, touchCount);
    } else if (candidateLen == touchCount - 1) {
      // User typed one extra key (e.g., "helllo" for "hello").
      // Try skipping each touch position and take the best alignment.
      return scoreWithSkippedTouch(candidate, touchXs, touchYs, touchCount);
    } else if (candidateLen == touchCount + 1) {
      // User missed a key (e.g., "helo" for "hello").
      // Try skipping each candidate character and take the best alignment.
      return scoreWithSkippedChar(candidate, touchXs, touchYs, touchCount);
    } else {
      // Larger length difference - flat penalty, not a plausible spatial match
      return -2.0f * Math.abs(candidateLen - touchCount);
    }
  }

  /**
   * Scores a candidate that exactly matches the touch count (1:1 alignment).
   *
   * <p>logP = Σ −d²ᵢ / (2σ²) for each character i
   */
  private float scoreAligned(
      @NonNull String candidate, float[] touchXs, float[] touchYs, int touchCount) {
    float logP = 0f;
    int charIndex = 0;
    for (int i = 0; i < candidate.length(); ) {
      int cp = Character.codePointAt(candidate, i);
      int lowerCp = Character.toLowerCase(cp);

      float[] center = mKeyCenters.get(lowerCp);
      if (center != null && charIndex < touchCount && touchXs[charIndex] >= 0) {
        float dx = touchXs[charIndex] - center[0];
        float dy = touchYs[charIndex] - center[1];
        float distSq = dx * dx + dy * dy;
        logP += -distSq / (2f * mSigmaSquared);
      }

      i += Character.charCount(cp);
      charIndex++;
    }
    return logP;
  }

  /**
   * Scores when candidate is 1 shorter than touches (extra keystroke).
   * Tries skipping each touch position, returns best score minus a skip penalty.
   *
   * <p>penalty = −2.5 (strong enough to prevent ultra-common short words from
   * dominating same-length spatial matches on compact keyboards)
   */
  private float scoreWithSkippedTouch(
      @NonNull String candidate, float[] touchXs, float[] touchYs, int touchCount) {
    int candidateLen = candidate.codePointCount(0, candidate.length());
    float bestScore = Float.NEGATIVE_INFINITY;

    for (int skipTouch = 0; skipTouch < touchCount; skipTouch++) {
      float logP = 0f;
      int charIndex = 0;
      int touchIndex = 0;
      for (int i = 0; i < candidate.length() && charIndex < candidateLen; ) {
        // Skip the designated touch position
        if (touchIndex == skipTouch) {
          touchIndex++;
        }
        int cp = Character.codePointAt(candidate, i);
        int lowerCp = Character.toLowerCase(cp);

        float[] center = mKeyCenters.get(lowerCp);
        if (center != null && touchIndex < touchCount && touchXs[touchIndex] >= 0) {
          float dx = touchXs[touchIndex] - center[0];
          float dy = touchYs[touchIndex] - center[1];
          float distSq = dx * dx + dy * dy;
          logP += -distSq / (2f * mSigmaSquared);
        }

        i += Character.charCount(cp);
        charIndex++;
        touchIndex++;
      }
      if (logP > bestScore) {
        bestScore = logP;
      }
    }
    // Apply skip penalty: −2.5 for one extra keystroke
    return bestScore - 2.5f;
  }

  /**
   * Scores when candidate is 1 longer than touches (missed keystroke).
   * Tries skipping each candidate character, returns best score minus a skip penalty.
   *
   * <p>penalty = −2.5 (strong enough to prevent length-mismatched candidates
   * from outranking well-matched same-length alternatives)
   */
  private float scoreWithSkippedChar(
      @NonNull String candidate, float[] touchXs, float[] touchYs, int touchCount) {
    // Build array of candidate code points for easy indexed access
    int candidateLen = candidate.codePointCount(0, candidate.length());
    int[] codePoints = new int[candidateLen];
    int idx = 0;
    for (int i = 0; i < candidate.length(); ) {
      codePoints[idx++] = Character.codePointAt(candidate, i);
      i += Character.charCount(codePoints[idx - 1]);
    }

    float bestScore = Float.NEGATIVE_INFINITY;

    for (int skipChar = 0; skipChar < candidateLen; skipChar++) {
      float logP = 0f;
      int touchIndex = 0;
      for (int ci = 0; ci < candidateLen; ci++) {
        if (ci == skipChar) continue;
        int lowerCp = Character.toLowerCase(codePoints[ci]);

        float[] center = mKeyCenters.get(lowerCp);
        if (center != null && touchIndex < touchCount && touchXs[touchIndex] >= 0) {
          float dx = touchXs[touchIndex] - center[0];
          float dy = touchYs[touchIndex] - center[1];
          float distSq = dx * dx + dy * dy;
          logP += -distSq / (2f * mSigmaSquared);
        }
        touchIndex++;
      }
      if (logP > bestScore) {
        bestScore = logP;
      }
    }
    // Apply skip penalty: −2.5 for one missed keystroke
    return bestScore - 2.5f;
  }

  /**
   * Returns the expected key center position for a character, or null if not on the keyboard.
   *
   * @param codePoint the character code point (will be lowercased)
   * @return float[2] {x, y} or null
   */
  public float[] getKeyCenter(int codePoint) {
    return mKeyCenters.get(Character.toLowerCase(codePoint));
  }

  public float getSigmaSquared() {
    return mSigmaSquared;
  }

  public float getAvgKeyWidth() {
    return mAvgKeyWidth;
  }
}
