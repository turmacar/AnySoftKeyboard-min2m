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
      // avgKeyWidth = Σ(keyWidth) / N
      mAvgKeyWidth = totalWidth / letterKeyCount;
      // σ = avgKeyWidth / 2
      float sigma = mAvgKeyWidth * 0.5f;
      // σ² = σ × σ
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

    // Only score candidates matching the touch count exactly for now.
    // Edit-distance candidates (different length) get a flat penalty instead.
    int candidateLen = candidate.codePointCount(0, candidate.length());
    if (candidateLen != touchCount) {
      // penalty = -2.0 × |candidateLength − touchCount|
      return -2.0f * Math.abs(candidateLen - touchCount);
    }

    float logP = 0f;
    int charIndex = 0;
    for (int i = 0; i < candidate.length(); ) {
      int cp = Character.codePointAt(candidate, i);
      int lowerCp = Character.toLowerCase(cp);

      float[] center = mKeyCenters.get(lowerCp);
      if (center != null && charIndex < touchCount && touchXs[charIndex] >= 0) {
        // dx = touchX − centerX,  dy = touchY − centerY
        float dx = touchXs[charIndex] - center[0];
        float dy = touchYs[charIndex] - center[1];
        // d² = dx² + dy²
        float distSq = dx * dx + dy * dy;
        // logP += −d² / (2σ²)   [Gaussian log-likelihood per keystroke]
        logP += -distSq / (2f * mSigmaSquared);
      }
      // If no center found (e.g., apostrophe), skip scoring that position

      i += Character.charCount(cp);
      charIndex++;
    }

    return logP;
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
