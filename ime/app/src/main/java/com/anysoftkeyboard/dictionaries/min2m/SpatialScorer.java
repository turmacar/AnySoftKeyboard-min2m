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
 * <p>All coordinates are in Minuum's normalized [0,1] space. Key centers and touch positions
 * are normalized at registration/query time using the keyboard's bounding box. This makes
 * sigma values device/resolution-independent and allows direct use of Minuum's tuned parameters.
 *
 * <p>Uses a Gaussian model: each touch contributes log P = -distance² / (2σ²), summed across all
 * character positions.
 */
public class SpatialScorer {
  /** Map from lowercase character code to its key center in normalized [0,1] space. */
  private final Map<Integer, float[]> mKeyCenters = new HashMap<>();

  /**
   * Normal Gaussian σ² in normalized space — forgiving, handles sloppy typing.
   * Minuum: 0.0577 (~1.5 key widths on a 26-key row).
   */
  private float mSigmaSquared = 1f;

  /**
   * Tight Gaussian σ² in normalized space — precise, favors exactly-hit keys.
   * Minuum: 0.0077 (~0.2 key widths). Being 1 key off produces a massive penalty
   * (~-12.5 per char on a 26-key 1D keyboard), giving spatial evidence strong
   * discrimination without needing α/β weight distortion.
   */
  private float mTightSigmaSquared = 1f;

  /** Average key width in pixels (pre-normalization), for diagnostics. */
  private float mAvgKeyWidth = 1f;

  /**
   * Normal sigma from Minuum's InitializeSimpleDisambiguateParams.
   * 0.0577 normalized ≈ 1.5 key widths on a 26-key row.
   */
  private static final float NORMAL_SIGMA = 0.0577f;

  /**
   * Tight sigma for precision discrimination. Minuum used 0.0077 but that's
   * too tight for our touch precision — both correct and 1-key-off touches
   * get equally massive penalties, eliminating discrimination. At 0.02
   * (~0.5 key widths on a 26-key row), being 1 key off gets penalty
   * -d²/(2σ²) ≈ -0.038²/0.0008 ≈ -1.9 per char, while a centered touch
   * gets ~0. This provides strong discrimination without saturating.
   */
  private static final float TIGHT_SIGMA = 0.02f;

  /** Keyboard bounding box in pixels, for normalizing touch coordinates. */
  private float mKeyboardMinX;
  private float mKeyboardMaxX;
  private float mKeyboardMinY;
  private float mKeyboardMaxY;
  private float mKeyboardWidth = 1f;
  private float mKeyboardHeight = 1f;

  private boolean mHasKeyboard = false;

  /**
   * Registers the current keyboard geometry. Normalizes all key centers to [0,1]
   * coordinate space using the keyboard's bounding box, matching Minuum's approach.
   *
   * @param keys the list of keys from the current keyboard
   */
  public void registerKeyboard(@NonNull List<Keyboard.Key> keys) {
    mKeyCenters.clear();

    // First pass: compute bounding box and average key width in pixel space
    float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
    float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
    float totalWidth = 0;
    int letterKeyCount = 0;

    // Temporary pixel-space centers
    Map<Integer, float[]> pixelCenters = new HashMap<>();

    for (Keyboard.Key key : keys) {
      int code = key.getPrimaryCode();
      if (code <= 0) continue;

      float cx = key.x + key.width / 2f;
      float cy = key.y + key.height / 2f;
      float left = key.x;
      float right = key.x + key.width;
      float top = key.y;
      float bottom = key.y + key.height;

      if (left < minX) minX = left;
      if (right > maxX) maxX = right;
      if (top < minY) minY = top;
      if (bottom > maxY) maxY = bottom;

      int lowerCode = Character.toLowerCase(code);
      if (!pixelCenters.containsKey(lowerCode)) {
        pixelCenters.put(lowerCode, new float[] {cx, cy});
      }

      if (Character.isLetter(code)) {
        totalWidth += key.width;
        letterKeyCount++;
      }
    }

    // Store bounding box for normalizing touch coordinates later
    mKeyboardMinX = minX;
    mKeyboardMaxX = maxX;
    mKeyboardMinY = minY;
    mKeyboardMaxY = maxY;
    mKeyboardWidth = Math.max(1f, maxX - minX);
    mKeyboardHeight = Math.max(1f, maxY - minY);

    if (letterKeyCount > 0) {
      mAvgKeyWidth = totalWidth / letterKeyCount;
    }

    // Second pass: normalize centers to [0,1]
    for (Map.Entry<Integer, float[]> entry : pixelCenters.entrySet()) {
      float[] px = entry.getValue();
      float nx = (px[0] - minX) / mKeyboardWidth;
      float ny = (px[1] - minY) / mKeyboardHeight;
      mKeyCenters.put(entry.getKey(), new float[] {nx, ny});
    }

    // Use Minuum's tuned sigma values directly in normalized space
    mSigmaSquared = NORMAL_SIGMA * NORMAL_SIGMA;
    mTightSigmaSquared = TIGHT_SIGMA * TIGHT_SIGMA;

    mHasKeyboard = !mKeyCenters.isEmpty();
  }

  public boolean hasKeyboard() {
    return mHasKeyboard;
  }

  /**
   * Normalizes a pixel-space touch X coordinate to [0,1] using the keyboard bounding box.
   */
  public float normalizeTouchX(float pixelX) {
    return (pixelX - mKeyboardMinX) / mKeyboardWidth;
  }

  /**
   * Normalizes a pixel-space touch Y coordinate to [0,1] using the keyboard bounding box.
   */
  public float normalizeTouchY(float pixelY) {
    return (pixelY - mKeyboardMinY) / mKeyboardHeight;
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

  public float getTightSigmaSquared() {
    return mTightSigmaSquared;
  }

  public float getAvgKeyWidth() {
    return mAvgKeyWidth;
  }

  /** Returns the key centers map for spatial index construction. */
  @NonNull
  public Map<Integer, float[]> getKeyCenters() {
    return mKeyCenters;
  }

  /**
   * Returns whether the current keyboard is a 1D layout (all keys on one row).
   * Detected by checking if the y-coordinate variance across letter keys is
   * small relative to the average key height.
   */
  public boolean is1D() {
    if (!mHasKeyboard) return false;
    float sumY = 0, sumYSq = 0;
    int count = 0;
    for (Map.Entry<Integer, float[]> entry : mKeyCenters.entrySet()) {
      if (Character.isLetter(entry.getKey())) {
        float y = entry.getValue()[1];
        sumY += y;
        sumYSq += y * y;
        count++;
      }
    }
    if (count < 2) return true;
    float meanY = sumY / count;
    float variance = sumYSq / count - meanY * meanY;
    // If std dev of y < half the average key width, it's effectively 1D
    return Math.sqrt(variance) < mAvgKeyWidth * 0.5f;
  }
}
