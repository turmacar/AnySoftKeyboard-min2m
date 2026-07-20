package com.anysoftkeyboard.dictionaries.min2m;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.anysoftkeyboard.base.utils.Logger;
import com.anysoftkeyboard.dictionaries.Dictionary;
import com.anysoftkeyboard.dictionaries.DictionaryAddOnAndBuilder;
import com.anysoftkeyboard.dictionaries.DictionaryBackgroundLoader;
import com.anysoftkeyboard.dictionaries.Suggest;
import com.anysoftkeyboard.dictionaries.SuggestionsProvider;
import com.anysoftkeyboard.dictionaries.WordComposer;
import com.anysoftkeyboard.keyboards.Keyboard;
import com.anysoftkeyboard.quicktextkeys.TagsExtractor;
import com.anysoftkeyboard.quicktextkeys.TagsExtractorImpl;
import com.anysoftkeyboard.utils.IMEUtil;
import com.menny.android.anysoftkeyboard.BuildConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Alternative suggestion engine using a 307K-word open-source vocabulary with Bayesian spatial
 * disambiguation (HMM scoring), kd-tree candidate search, n-gram context boosting, and
 * shift-aware composing case mode.
 *
 * <p>Delegates user dictionary, contacts, abbreviations, and auto-text to ASK's existing {@link
 * SuggestionsProvider}.
 */
public class Min2mSuggest implements Suggest {
  private static final String TAG = "Min2mSuggest";

  public enum ComposingCaseMode {
    LOWER,
    TITLE,
    UPPER
  }

  // Frequency tiers matching SuggestImpl conventions so the candidate view
  // treats typed-word, valid-typed, and corrections the same way.
  private static final int TYPED_WORD_FREQUENCY = Integer.MAX_VALUE;
  private static final int VALID_TYPED_WORD_FREQUENCY = Integer.MAX_VALUE - 25;

  @NonNull private final SuggestionsProvider mSuggestionsProvider;
  @NonNull private final Min2mVocabulary mVocabulary;
  @NonNull private final SpatialScorer mSpatialScorer;
  @NonNull private final HMMScorer mHmmScorer;
  @NonNull private final BayesianCandidateRanker mRanker;
  @NonNull private final SpatialIndex mSpatialIndex;

  private final List<CharSequence> mSuggestions = new ArrayList<>();
  private final List<CharSequence> mNextSuggestions = new ArrayList<>();
  private final List<CharSequence> mStringPool = new ArrayList<>();
  private int[] mPriorities = new int[12];

  @NonNull private final Locale mLocale = Locale.getDefault();
  @NonNull private TagsExtractor mTagsSearcher = TagsExtractorImpl.NO_OP;

  private int mPrefMaxSuggestions = 12;
  private int mCorrectSuggestionIndex = -1;
  private boolean mEnabledSuggestions;
  @NonNull private ComposingCaseMode mComposingCaseMode = ComposingCaseMode.LOWER;

  public Min2mSuggest(@NonNull Context context) {
    mSuggestionsProvider = new SuggestionsProvider(context);
    mVocabulary = new Min2mVocabulary();
    mSpatialScorer = new SpatialScorer();
    mHmmScorer = new HMMScorer(mSpatialScorer);
    mRanker = new BayesianCandidateRanker();
    mSpatialIndex = new SpatialIndex();
    setMaxSuggestions(mPrefMaxSuggestions);

    // Load vocabulary on a background thread to avoid StrictMode disk-read
    // violations on the main thread. Suggestions gracefully degrade (empty)
    // until loading completes (~1s).
    new Thread(() -> {
      mVocabulary.open(context);
      if (mVocabulary.isOpen()) {
        mRanker.setMaxFrequency(mVocabulary.getMaxFrequency());
        // If keyboard was registered before vocab finished loading,
        // build the spatial index now that we have both pieces.
        if (mSpatialScorer.hasKeyboard()) {
          mSpatialIndex.build(
              mVocabulary, mSpatialScorer.getKeyCenters(), mSpatialScorer.is1D());
        }
      }
      Logger.d(TAG, "Vocabulary background load complete");
    }, "min2m-vocab-load").start();
  }

  @VisibleForTesting
  public Min2mSuggest(@NonNull SuggestionsProvider provider, @NonNull Min2mVocabulary vocabulary) {
    mSuggestionsProvider = provider;
    mVocabulary = vocabulary;
    mSpatialScorer = new SpatialScorer();
    mHmmScorer = new HMMScorer(mSpatialScorer);
    mRanker = new BayesianCandidateRanker();
    mSpatialIndex = new SpatialIndex();
    setMaxSuggestions(mPrefMaxSuggestions);
  }

  /**
   * Registers the current keyboard's key geometry for spatial scoring.
   * Call this when the keyboard layout changes.
   */
  public void registerKeyboard(@NonNull java.util.List<Keyboard.Key> keys) {
    mSpatialScorer.registerKeyboard(keys);
    Logger.d(TAG, "Registered keyboard with %d keys for spatial scoring", keys.size());

    // Rebuild spatial index on a background thread. The index maps every
    // vocabulary word to a position vector based on key centers, then builds
    // one kd-tree per word length. Queries become O(log n) instead of
    // brute-force O(n). Following Minuum's wsKdTreeQuery architecture.
    if (mVocabulary.isOpen()) {
      new Thread(() -> {
        mSpatialIndex.build(mVocabulary, mSpatialScorer.getKeyCenters(), mSpatialScorer.is1D());
      }, "min2m-kdtree-build").start();
    }
  }

  public void setComposingCaseMode(@NonNull ComposingCaseMode composingCaseMode) {
    mComposingCaseMode = composingCaseMode;
  }

  @NonNull
  public CharSequence applyComposingCaseMode(@NonNull CharSequence text) {
    if (text.length() == 0) {
      return text;
    }

    final String word = text.toString();
    final String lowerWord = word.toLowerCase(mLocale);

    // English pronoun I and its contractions should remain capitalized in
    // LOWER/TITLE, but still honor UPPER mode.
    final String pronounNormalized = normalizePronounI(lowerWord);
    if (pronounNormalized != null) {
      switch (mComposingCaseMode) {
        case UPPER:
          return pronounNormalized.toUpperCase(mLocale);
        case TITLE:
        case LOWER:
        default:
          return pronounNormalized;
      }
    }

    switch (mComposingCaseMode) {
      case UPPER:
        return word.toUpperCase(mLocale);
      case TITLE:
        if (word.length() == 1) {
          return word.toUpperCase(mLocale);
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(mLocale);
      case LOWER:
      default:
        return word.toLowerCase(mLocale);
    }
  }

  @Nullable
  private String normalizePronounI(@NonNull String lowerWord) {
    switch (lowerWord) {
      case "i":
        return "I";
      case "i'm":
      case "im":
        return "I'm";
      case "i've":
      case "ive":
        return "I've";
      case "i'll":
        return "I'll";
      case "i'd":
        return "I'd";
      default:
        return null;
    }
  }

  private void normalizePronounSuggestions(List<CharSequence> suggestions) {
    for (int i = 0; i < suggestions.size(); i++) {
      CharSequence s = suggestions.get(i);
      String normalized = normalizePronounI(s.toString().toLowerCase(mLocale));
      if (normalized == null) {
        continue;
      }
      CharSequence transformed = applyComposingCaseMode(normalized);
      if (s instanceof StringBuilder) {
        ((StringBuilder) s).setLength(0);
        ((StringBuilder) s).append(transformed);
      } else {
        suggestions.set(i, transformed);
      }
      // Ensure autocorrect can pick the normalized pronoun forms.
      if (i == 0 || mCorrectSuggestionIndex < 0) {
        mCorrectSuggestionIndex = i;
      }
    }
  }

  @Override
  public void setCorrectionMode(
      boolean enabledSuggestions, int maxLengthDiff, int maxDistance, boolean splitWords) {
    mEnabledSuggestions = enabledSuggestions;
    // maxLengthDiff, maxDistance, splitWords are ASK fuzzy-match params;
    // min2m uses spatial/HMM scoring instead.
  }

  @Override
  @VisibleForTesting
  public boolean isSuggestionsEnabled() {
    return mEnabledSuggestions;
  }

  @Override
  public void closeDictionaries() {
    mSuggestionsProvider.close();
  }

  @Override
  public void setupSuggestionsForKeyboard(
      @NonNull List<DictionaryAddOnAndBuilder> dictionaryBuilders,
      @NonNull DictionaryBackgroundLoader.Listener cb) {
    // Let the SuggestionsProvider handle user/contacts/abbreviation dictionaries
    if (mEnabledSuggestions && dictionaryBuilders.size() > 0) {
      mSuggestionsProvider.setupSuggestionsForKeyboard(dictionaryBuilders, cb);
    } else {
      closeDictionaries();
    }
  }

  @Override
  public void setMaxSuggestions(int maxSuggestions) {
    if (maxSuggestions < 1 || maxSuggestions > 100) {
      throw new IllegalArgumentException("maxSuggestions must be between 1 and 100");
    }
    mPrefMaxSuggestions = maxSuggestions;
    mPriorities = new int[mPrefMaxSuggestions];
    mSuggestions.clear();
    while (mStringPool.size() < mPrefMaxSuggestions) {
      mStringPool.add(new StringBuilder(Dictionary.MAX_WORD_LENGTH));
    }
  }

  @Override
  public void resetNextWordSentence() {
    mNextSuggestions.clear();
    mSuggestionsProvider.resetNextWordSentence();
  }

  @Override
  public List<CharSequence> getNextSuggestions(
      CharSequence previousWord, boolean inAllUpperCaseState) {
    if (previousWord.length() == 0) return Collections.emptyList();

    mNextSuggestions.clear();

    if (isValidWord(previousWord)) {
      String prev = previousWord.toString();
      // Get next-word suggestions from ASK's user-learned dictionary
      mSuggestionsProvider.getNextWords(prev, mNextSuggestions, mPrefMaxSuggestions);

      // Supplement with bigram predictions from our corpus data
      if (mVocabulary.isOpen()) {
        List<String> bigramPredictions = mVocabulary.getNextWords(prev);
        for (String prediction : bigramPredictions) {
          // Avoid duplicates with ASK's predictions
          boolean alreadyPresent = false;
          for (CharSequence existing : mNextSuggestions) {
            if (prediction.contentEquals(existing)) {
              alreadyPresent = true;
              break;
            }
          }
          if (!alreadyPresent && mNextSuggestions.size() < mPrefMaxSuggestions) {
            mNextSuggestions.add(prediction);
          }
        }
      }

      if (inAllUpperCaseState) {
        for (int i = 0; i < mNextSuggestions.size(); i++) {
          mNextSuggestions.set(i, mNextSuggestions.get(i).toString().toUpperCase(mLocale));
        }
      } else if (mComposingCaseMode == ComposingCaseMode.TITLE) {
        for (int i = 0; i < mNextSuggestions.size(); i++) {
          mNextSuggestions.set(i, applyComposingCaseMode(mNextSuggestions.get(i)));
        }
      }
    }
    return mNextSuggestions;
  }

  // --- 3-Phase Progressive Disambiguation ---
  // Modeled on Minuum's native engine which takes a threshold parameter and
  // returns phasesCompleted (1-3). Each phase widens the search:
  //   Phase 1 (Tight): exact-length only, small K, early exit if confident
  //   Phase 2 (Relaxed): ±1 length (edit distance), larger K
  //   Phase 3 (Loose): ±2 length, full K, plus space-omission candidates

  /**
   * Tracks the typed word's spatial priority across all disambiguation phases.
   * Set when the typed word appears in the scored candidate list. Used for
   * auto-correct decision: only auto-correct if the spatial winner beats this.
   * Reset to -1 at the start of each getSuggestions() call.
   */
  private int mTypedWordSpatialPriority = -1;

  /** KD-tree K per phase. Phase 1 is tight, Phase 3 is the current full search. */
  private static final int PHASE1_K = 40;
  private static final int PHASE2_K = 70;
  private static final int PHASE3_K = 100;

  /**
   * Confidence threshold for Phase 1 early exit. If the top candidate's
   * Bayesian score exceeds this, we skip Phases 2 and 3. With pure Bayesian
   * scoring (α=1, β=1), scores are natural log-probabilities:
   * - Common word + good spatial match: ~-3 to -5
   * - Medium word + decent match: ~-7 to -10
   * - Rare word or poor match: < -15
   *
   * Threshold of -8.0 means "reasonably common word with decent spatial fit".
   */
  private static final float PHASE1_CONFIDENCE_THRESHOLD = -8.0f;

  @Override
  public List<CharSequence> getSuggestions(WordComposer wordComposer) {
    if (!mEnabledSuggestions) return Collections.emptyList();

    mCorrectSuggestionIndex = -1;
    mTypedWordSpatialPriority = -1;
    final boolean isFirstCharCapitalized = mComposingCaseMode == ComposingCaseMode.TITLE;
    final boolean isAllUpperCase = mComposingCaseMode == ComposingCaseMode.UPPER;
    collectGarbage();
    java.util.Arrays.fill(mPriorities, 0);

    final String typedOriginalWord = wordComposer.getTypedWord().toString();
    if (typedOriginalWord.isEmpty()) return Collections.emptyList();

    final String lowerOriginalWord = typedOriginalWord.toLowerCase(mLocale);

    // Handle tag search (emoji/sticker lookup via ':' prefix)
    if (wordComposer.isAtTagsSearchState() && mTagsSearcher.isEnabled()) {
      return mTagsSearcher.getOutputForTag(lowerOriginalWord.substring(1), wordComposer);
    }

    // Position 0: the key detector's output (shown in composing text).
    // The spatial scorer determines the real intended word at position 1.
    mSuggestions.add(0, applyComposingCaseMode(typedOriginalWord));
    mPriorities[0] = TYPED_WORD_FREQUENCY;

    final boolean hasTouchData =
        wordComposer.hasTouchCoordinates() && mSpatialScorer.hasKeyboard();
    final int touchCount = wordComposer.codePointCount();
    int typedWordSpatialPriority = -1;

    if (mVocabulary.isOpen()) {
      // Extract touch coordinates and normalize to [0,1] using the keyboard
      // bounding box. Matches Minuum's normalized coordinate space so sigma
      // values from Minuum's tuned parameters apply directly.
      float[] touchXs = new float[touchCount];
      float[] touchYs = new float[touchCount];
      if (hasTouchData) {
        for (int i = 0; i < touchCount; i++) {
          touchXs[i] = mSpatialScorer.normalizeTouchX(wordComposer.getTouchX(i));
          touchYs[i] = mSpatialScorer.normalizeTouchY(wordComposer.getTouchY(i));
        }
      }

      // Build n-gram context set for boosting
      java.util.Set<String> bigramNextWords = new java.util.HashSet<>();
      for (CharSequence nw : mNextSuggestions) {
        bigramNextWords.add(nw.toString().toLowerCase(mLocale));
      }

      long tTotal0 = System.nanoTime();
      int phasesCompleted;

      if (hasTouchData && mSpatialIndex.isBuilt()) {
        // === 3-Phase progressive disambiguation (kd-tree path) ===
        phasesCompleted = runProgressiveDisambiguation(
            touchXs, touchYs, touchCount, lowerOriginalWord,
            isFirstCharCapitalized, isAllUpperCase, bigramNextWords);

        // mTypedWordSpatialPriority was set during scoring if the typed
        // word appeared in any phase's candidate list
        typedWordSpatialPriority = mTypedWordSpatialPriority;
      } else {
        // Fallback: brute-force candidate pool (pre-kd-tree path)
        phasesCompleted = 0;
        java.util.Set<Character> firstCharCandidates = new java.util.HashSet<>();
        for (char ch = 'a'; ch <= 'z'; ch++) {
          firstCharCandidates.add(ch);
        }
        List<Min2mVocabulary.CandidateWord> candidates = mVocabulary.getSpatialCandidates(
            firstCharCandidates, touchCount, lowerOriginalWord, mPrefMaxSuggestions * 3);
        typedWordSpatialPriority = scoreCandidates(candidates, touchXs, touchYs,
            touchCount, hasTouchData, lowerOriginalWord, isFirstCharCapitalized,
            isAllUpperCase, bigramNextWords);
      }

      if (BuildConfig.DEBUG) {
        long tTotal = System.nanoTime() - tTotal0;
        Logger.d(TAG, "perf: total=%.2fms, phases=%d", tTotal / 1e6, phasesCompleted);
      }
    }

    // User-learned words and contacts
    mSuggestionsProvider.getAbbreviations(wordComposer, mAskBridgeCallback);
    mSuggestionsProvider.getAutoText(wordComposer, mAskBridgeCallback);
    mSuggestionsProvider.getUserAndContactsSuggestions(wordComposer, mAskBridgeCallback);

    // --- Auto-correction ---
    // Auto-correct to position 1 only if it scored higher than the typed word.
    // If the typed word IS the spatial winner (e.g., user typed "I" correctly
    // and it has the best spatial+frequency score), don't auto-correct away.
    if (mSuggestions.size() > 1 && mPriorities[1] > 0) {
      if (typedWordSpatialPriority < 0 || mPriorities[1] > typedWordSpatialPriority) {
        mCorrectSuggestionIndex = 1;
      }
    }

    normalizePronounSuggestions(mSuggestions);
    IMEUtil.removeDupes(mSuggestions, mStringPool);

    // Clamp correction index: removeDupes may have shrunk the list,
    // and ASK bridge callbacks may have set mCorrectSuggestionIndex
    // to a position that no longer exists.
    if (mCorrectSuggestionIndex >= mSuggestions.size()) {
      mCorrectSuggestionIndex = mSuggestions.size() > 1 ? 1 : -1;
    }

    if (BuildConfig.DEBUG && mSuggestions.size() > 1) {
      StringBuilder logMsg = new StringBuilder();
      logMsg.append("typed='").append(typedOriginalWord).append("' → [");
      int logCount = Math.min(mSuggestions.size(), 6);
      for (int i = 0; i < logCount; i++) {
        if (i > 0) logMsg.append(", ");
        logMsg.append(mSuggestions.get(i));
        if (i < mPriorities.length) {
          logMsg.append("(").append(mPriorities[i]).append(")");
        }
        if (i == mCorrectSuggestionIndex) logMsg.append("*");
      }
      Logger.d(TAG, logMsg.toString());
    }
    return mSuggestions;
  }

  /**
   * Runs 3-phase progressive disambiguation using the kd-tree spatial index.
   *
   * <p>Phase 1 (Tight): exact-length candidates only, small K, early exit if confident.
   * Phase 2 (Relaxed): ±1 length for edit distance, larger K.
   * Phase 3 (Loose): ±2 length, full K, plus space-omission candidates.
   *
   * <p>Modeled on Minuum's native {@code Disambiguator_disambiguate()} which accepted a
   * threshold parameter and returned {@code phasesCompleted} (1-3).
   *
   * @return number of phases completed (1, 2, or 3)
   */
  private int runProgressiveDisambiguation(
      float[] touchXs, float[] touchYs, int touchCount,
      @NonNull String lowerOriginalWord,
      boolean isFirstCharCapitalized, boolean isAllUpperCase,
      @NonNull java.util.Set<String> bigramNextWords) {

    // === Phase 1 (Tight): exact-length only, small K ===
    List<Min2mVocabulary.CandidateWord> candidates =
        mSpatialIndex.query(touchXs, touchYs, touchCount, PHASE1_K, 0, 0);

    // Always supplement with prefix matches for the typed word
    for (Min2mVocabulary.CandidateWord pw : mVocabulary.getPrefixMatches(lowerOriginalWord, 10)) {
      candidates.add(pw);
    }

    float topScore = scoreCandidatesAndGetTopScore(candidates, touchXs, touchYs,
        touchCount, true, lowerOriginalWord, isFirstCharCapitalized,
        isAllUpperCase, bigramNextWords);

    if (topScore > PHASE1_CONFIDENCE_THRESHOLD) {
      if (BuildConfig.DEBUG) {
        Logger.d(TAG, "Phase 1 confident (topScore=%.2f > %.2f), %d candidates",
            topScore, PHASE1_CONFIDENCE_THRESHOLD, candidates.size());
      }
      return 1;
    }

    // === Phase 2 (Relaxed): ±1 length, larger K ===
    // Query edit-distance length buckets not covered by Phase 1
    List<Min2mVocabulary.CandidateWord> phase2Candidates =
        mSpatialIndex.query(touchXs, touchYs, touchCount, PHASE2_K, -1, 1);

    // Add more prefix matches
    for (Min2mVocabulary.CandidateWord pw : mVocabulary.getPrefixMatches(lowerOriginalWord, 20)) {
      phase2Candidates.add(pw);
    }

    scoreCandidatesAndGetTopScore(phase2Candidates, touchXs, touchYs,
        touchCount, true, lowerOriginalWord, isFirstCharCapitalized,
        isAllUpperCase, bigramNextWords);

    // Check if Phase 2 produced a confident result
    topScore = getTopCandidateScore();
    if (topScore > PHASE1_CONFIDENCE_THRESHOLD) {
      if (BuildConfig.DEBUG) {
        Logger.d(TAG, "Phase 2 confident (topScore=%.2f), %d new candidates",
            topScore, phase2Candidates.size());
      }
      return 2;
    }

    // === Phase 3 (Loose): ±2 length, full K, space-omission ===
    List<Min2mVocabulary.CandidateWord> phase3Candidates =
        mSpatialIndex.query(touchXs, touchYs, touchCount, PHASE3_K, -2, 2);

    // For words longer than the kd-tree covers (>20 chars), fall back to brute-force
    if (touchCount > 20) {
      java.util.Set<Character> allChars = new java.util.HashSet<>();
      for (char ch = 'a'; ch <= 'z'; ch++) allChars.add(ch);
      for (Min2mVocabulary.CandidateWord c : mVocabulary.getSpatialCandidates(
          allChars, touchCount, lowerOriginalWord, 50)) {
        phase3Candidates.add(c);
      }
    }

    scoreCandidatesAndGetTopScore(phase3Candidates, touchXs, touchYs,
        touchCount, true, lowerOriginalWord, isFirstCharCapitalized,
        isAllUpperCase, bigramNextWords);

    // Space-omission detection: try splitting the input into two words
    // Minimum 6 touches (3+3) to avoid nonsense splits like "by kw" from 4-char input
    if (touchCount >= 6) {
      generateSpaceOmissionCandidates(touchXs, touchYs, touchCount,
          isFirstCharCapitalized, isAllUpperCase, bigramNextWords);
    }

    if (BuildConfig.DEBUG) {
      Logger.d(TAG, "Phase 3 complete (topScore=%.2f), %d new candidates + space-omission",
          getTopCandidateScore(), phase3Candidates.size());
    }
    return 3;
  }

  /**
   * Generates space-omission candidates by splitting the touch sequence at each position
   * and scoring each half as an independent word. If a split produces two valid words
   * whose combined score beats existing candidates, the joined form is inserted.
   *
   * <p>Example: "helloworld" (10 touches) → try "hello world" (5+5),
   * "hell oworld" (4+6), etc. Only splits where both halves are valid words
   * and each half is at least 2 characters are considered.
   */
  private void generateSpaceOmissionCandidates(
      float[] touchXs, float[] touchYs, int touchCount,
      boolean isFirstCharCapitalized, boolean isAllUpperCase,
      @NonNull java.util.Set<String> bigramNextWords) {

    // Try each split point (minimum 3 characters per half)
    for (int splitAt = 3; splitAt <= touchCount - 3; splitAt++) {
      // Score the first half
      float[] firstXs = new float[splitAt];
      float[] firstYs = new float[splitAt];
      System.arraycopy(touchXs, 0, firstXs, 0, splitAt);
      System.arraycopy(touchYs, 0, firstYs, 0, splitAt);

      // Get best candidate for first half
      List<Min2mVocabulary.CandidateWord> firstCandidates =
          mSpatialIndex.query(firstXs, firstYs, splitAt, 5, 0, 0);
      if (firstCandidates.isEmpty()) continue;

      // Score the second half
      int secondLen = touchCount - splitAt;
      float[] secondXs = new float[secondLen];
      float[] secondYs = new float[secondLen];
      System.arraycopy(touchXs, splitAt, secondXs, 0, secondLen);
      System.arraycopy(touchYs, splitAt, secondYs, 0, secondLen);

      List<Min2mVocabulary.CandidateWord> secondCandidates =
          mSpatialIndex.query(secondXs, secondYs, secondLen, 5, 0, 0);
      if (secondCandidates.isEmpty()) continue;

      // Score top candidates from each half
      Min2mVocabulary.CandidateWord bestFirst = null;
      float bestFirstScore = Float.NEGATIVE_INFINITY;
      for (Min2mVocabulary.CandidateWord c : firstCandidates) {
        if (!mVocabulary.isPlausibleWord(c.text)) continue;
        float spatialLogP = mHmmScorer.scoreWord(c.text, firstXs, firstYs, splitAt);
        float score = mRanker.score(c.frequency, spatialLogP,
            c.text.codePointCount(0, c.text.length()));
        if (score > bestFirstScore) {
          bestFirstScore = score;
          bestFirst = c;
        }
      }

      Min2mVocabulary.CandidateWord bestSecond = null;
      float bestSecondScore = Float.NEGATIVE_INFINITY;
      for (Min2mVocabulary.CandidateWord c : secondCandidates) {
        if (!mVocabulary.isPlausibleWord(c.text)) continue;
        float spatialLogP = mHmmScorer.scoreWord(c.text, secondXs, secondYs, secondLen);
        float score = mRanker.score(c.frequency, spatialLogP,
            c.text.codePointCount(0, c.text.length()));
        if (score > bestSecondScore) {
          bestSecondScore = score;
          bestSecond = c;
        }
      }

      if (bestFirst == null || bestSecond == null) continue;

      // Both halves must be real dictionary words — reject nonsense like "kw"
      if (!mVocabulary.isValidWord(bestFirst.text) || !mVocabulary.isValidWord(bestSecond.text)) {
        continue;
      }

      // Each half must be a good spatial match on its own.
      // With pure Bayesian scoring, a good 3-char common word scores ~-5,
      // a decent 4-char word ~-7. Threshold -8.0 allows reasonable matches.
      if (bestFirstScore < -8.0f || bestSecondScore < -8.0f) {
        continue;
      }

      // Combined score: SUM of both halves (not average) so the total is
      // directly comparable to a single-word HMM score over all touches.
      // The penalty models Minuum's pOmitSpace=0.01: ln(0.01) ≈ -4.6.
      float combinedScore = bestFirstScore + bestSecondScore - 5.0f;
      int scaledFreq = BayesianCandidateRanker.toIntPriority(combinedScore);

      // Build the joined suggestion "word1 word2"
      String joined = bestFirst.text + " " + bestSecond.text;
      StringBuilder sb = getStringBuilderFromPool(
          joined, isFirstCharCapitalized, isAllUpperCase);
      insertSuggestion(sb, scaledFreq);

      if (BuildConfig.DEBUG) {
        Logger.d(TAG, "Space-omission: '%s %s' (score=%.2f, split@%d)",
            bestFirst.text, bestSecond.text, combinedScore, splitAt);
      }
    }
  }

  /**
   * Scores a list of candidates and inserts them into the suggestion list.
   * Uses dual-sigma blending (normal + tight HMM evaluation).
   * Returns the spatial priority of the typed word if found, or -1.
   */
  private int scoreCandidates(
      @NonNull List<Min2mVocabulary.CandidateWord> candidates,
      float[] touchXs, float[] touchYs, int touchCount,
      boolean hasTouchData, @NonNull String lowerOriginalWord,
      boolean isFirstCharCapitalized, boolean isAllUpperCase,
      @NonNull java.util.Set<String> bigramNextWords) {
    int typedWordSpatialPriority = -1;
    float normalSigmaSq = mSpatialScorer.getSigmaSquared();
    float tightSigmaSq = mSpatialScorer.getTightSigmaSquared();

    for (Min2mVocabulary.CandidateWord candidate : candidates) {
      if (!candidate.text.equalsIgnoreCase(lowerOriginalWord)
          && !mVocabulary.isPlausibleWord(candidate.text)) {
        continue;
      }

      float bayesianScore;
      if (hasTouchData) {
        float normalLogP = mHmmScorer.scoreWord(
            candidate.text, touchXs, touchYs, touchCount, normalSigmaSq);
        float tightLogP = mHmmScorer.scoreWord(
            candidate.text, touchXs, touchYs, touchCount, tightSigmaSq);
        int candidateLen = candidate.text.codePointCount(0, candidate.text.length());
        bayesianScore = mRanker.score(candidate.frequency, normalLogP, tightLogP, candidateLen);
      } else {
        bayesianScore = mRanker.scoreFrequencyOnly(candidate.frequency);
      }

      if (bigramNextWords.contains(candidate.text)) {
        bayesianScore += 3.0f;
      }

      int scaledFreq = BayesianCandidateRanker.toIntPriority(bayesianScore);

      if (candidate.text.equalsIgnoreCase(lowerOriginalWord)) {
        typedWordSpatialPriority = scaledFreq;
      }

      StringBuilder sb = getStringBuilderFromPool(
          candidate.text, isFirstCharCapitalized, isAllUpperCase);
      insertSuggestion(sb, scaledFreq);
    }
    return typedWordSpatialPriority;
  }

  /**
   * Scores candidates and returns the top Bayesian score (not int-priority).
   * Uses dual-sigma blending. Used for confidence checking in progressive disambiguation.
   */
  private float scoreCandidatesAndGetTopScore(
      @NonNull List<Min2mVocabulary.CandidateWord> candidates,
      float[] touchXs, float[] touchYs, int touchCount,
      boolean hasTouchData, @NonNull String lowerOriginalWord,
      boolean isFirstCharCapitalized, boolean isAllUpperCase,
      @NonNull java.util.Set<String> bigramNextWords) {
    float topScore = Float.NEGATIVE_INFINITY;
    float normalSigmaSq = mSpatialScorer.getSigmaSquared();
    float tightSigmaSq = mSpatialScorer.getTightSigmaSquared();

    // Diagnostic: track top 3 candidates with score breakdowns
    String[] diagWords = BuildConfig.DEBUG ? new String[3] : null;
    float[] diagNormal = BuildConfig.DEBUG ? new float[3] : null;
    float[] diagTight = BuildConfig.DEBUG ? new float[3] : null;
    float[] diagFreq = BuildConfig.DEBUG ? new float[3] : null;
    float[] diagTotal = BuildConfig.DEBUG ? new float[3] : null;
    int diagCount = 0;

    for (Min2mVocabulary.CandidateWord candidate : candidates) {
      if (!candidate.text.equalsIgnoreCase(lowerOriginalWord)
          && !mVocabulary.isPlausibleWord(candidate.text)) {
        continue;
      }

      float bayesianScore;
      float normalLogP = 0f, tightLogP = 0f;
      if (hasTouchData) {
        normalLogP = mHmmScorer.scoreWord(
            candidate.text, touchXs, touchYs, touchCount, normalSigmaSq);
        tightLogP = mHmmScorer.scoreWord(
            candidate.text, touchXs, touchYs, touchCount, tightSigmaSq);
        int candidateLen = candidate.text.codePointCount(0, candidate.text.length());
        bayesianScore = mRanker.score(candidate.frequency, normalLogP, tightLogP, candidateLen);
      } else {
        bayesianScore = mRanker.scoreFrequencyOnly(candidate.frequency);
      }

      if (bigramNextWords.contains(candidate.text)) {
        bayesianScore += 3.0f;
      }

      // Track top 3 for diagnostics
      if (BuildConfig.DEBUG && hasTouchData) {
        float logFreq = (float) Math.log((double) candidate.frequency / mRanker.getMaxFrequency());
        if (diagCount < 3) {
          diagWords[diagCount] = candidate.text;
          diagNormal[diagCount] = normalLogP;
          diagTight[diagCount] = tightLogP;
          diagFreq[diagCount] = logFreq;
          diagTotal[diagCount] = bayesianScore;
          diagCount++;
        } else {
          // Replace the worst of the 3
          int worst = 0;
          for (int d = 1; d < 3; d++) {
            if (diagTotal[d] < diagTotal[worst]) worst = d;
          }
          if (bayesianScore > diagTotal[worst]) {
            diagWords[worst] = candidate.text;
            diagNormal[worst] = normalLogP;
            diagTight[worst] = tightLogP;
            diagFreq[worst] = logFreq;
            diagTotal[worst] = bayesianScore;
          }
        }
      }

      if (bayesianScore > topScore) {
        topScore = bayesianScore;
      }

      int scaledFreq = BayesianCandidateRanker.toIntPriority(bayesianScore);

      // Track typed word's spatial priority for auto-correct decision.
      // Must be done BEFORE insertSuggestion, which deduplicates against
      // position 0 (the typed word) and would prevent findTypedWordPriority
      // from finding it in the ranked list.
      if (candidate.text.equalsIgnoreCase(lowerOriginalWord)) {
        mTypedWordSpatialPriority = scaledFreq;
      }

      StringBuilder sb = getStringBuilderFromPool(
          candidate.text, isFirstCharCapitalized, isAllUpperCase);
      insertSuggestion(sb, scaledFreq);
    }

    if (BuildConfig.DEBUG && diagCount > 0) {
      // Sort diagnostics by total score descending
      for (int i = 0; i < diagCount - 1; i++) {
        for (int j = i + 1; j < diagCount; j++) {
          if (diagTotal[j] > diagTotal[i]) {
            String tw = diagWords[i]; diagWords[i] = diagWords[j]; diagWords[j] = tw;
            float tn = diagNormal[i]; diagNormal[i] = diagNormal[j]; diagNormal[j] = tn;
            float tt = diagTight[i]; diagTight[i] = diagTight[j]; diagTight[j] = tt;
            float tf = diagFreq[i]; diagFreq[i] = diagFreq[j]; diagFreq[j] = tf;
            float tl = diagTotal[i]; diagTotal[i] = diagTotal[j]; diagTotal[j] = tl;
          }
        }
      }
      StringBuilder sb = new StringBuilder("σ-diag: ");
      for (int d = 0; d < diagCount; d++) {
        if (d > 0) sb.append(" | ");
        sb.append(String.format("%s(f=%.1f n=%.1f t=%.1f →%.1f)",
            diagWords[d], diagFreq[d], diagNormal[d], diagTight[d], diagTotal[d]));
      }
      Logger.d(TAG, sb.toString());
    }

    return topScore;
  }

  /**
   * Returns the current top candidate's Bayesian score by reverse-mapping
   * the int priority at position 1 back to a score. Used for confidence
   * checking across phases.
   */
  private float getTopCandidateScore() {
    if (mSuggestions.size() <= 1 || mPriorities[1] <= 0) {
      return Float.NEGATIVE_INFINITY;
    }
    // Reverse the toIntPriority mapping: priority → score
    // priority = 1 + normalized * (MAX_VALUE/2 - 2)
    // normalized = (priority - 1) / (MAX_VALUE/2 - 2)
    // score = normalized * 60 - 60
    double normalized = (double) (mPriorities[1] - 1) / (Integer.MAX_VALUE / 2 - 2);
    return (float) (normalized * 60.0 - 60.0);
  }

  /** Bridge callback that inserts ASK dictionary results into our ranked suggestion list. */
  private final Dictionary.WordCallback mAskBridgeCallback =
      (word, wordOffset, wordLength, frequency, from) -> {
        StringBuilder sb = new StringBuilder(wordLength);
        sb.append(word, wordOffset, wordLength);
        insertSuggestion(sb, frequency);
        return true;
      };

  /**
   * Inserts a suggestion into the priority-sorted list, maintaining descending frequency order.
   * Matches the insertion logic from SuggestImpl.SuggestionCallback.
   */
  private void insertSuggestion(CharSequence suggestion, int frequency) {
    final int prefMaxSuggestions = mPrefMaxSuggestions;

    // Check if this duplicates the typed word
    if (mSuggestions.size() > 0 && TextUtils.equals(mSuggestions.get(0), suggestion)) {
      if (frequency >= VALID_TYPED_WORD_FREQUENCY) {
        mPriorities[0] = VALID_TYPED_WORD_FREQUENCY;
        mCorrectSuggestionIndex = 0;
      }
      return;
    }

    // Check if the list is full and this word is too low priority
    if (mSuggestions.size() >= prefMaxSuggestions
        && mPriorities[prefMaxSuggestions - 1] >= frequency) {
      return;
    }

    // Find insertion position (skip position 0 which is the typed word)
    int pos = 1;
    while (pos < mSuggestions.size() && pos < prefMaxSuggestions) {
      if (mPriorities[pos] < frequency
          || (mPriorities[pos] == frequency
              && suggestion.length() < mSuggestions.get(pos).length())) {
        break;
      }
      pos++;
    }

    if (pos >= prefMaxSuggestions) return;

    // Shift priorities down
    if (pos < prefMaxSuggestions - 1) {
      System.arraycopy(mPriorities, pos, mPriorities, pos + 1, prefMaxSuggestions - pos - 1);
    }
    mSuggestions.add(pos, suggestion);
    mPriorities[pos] = frequency;

    // Track correction index
    if (frequency >= Integer.MAX_VALUE / 2) {
      if (mCorrectSuggestionIndex < 0 || mPriorities[mCorrectSuggestionIndex] < frequency) {
        mCorrectSuggestionIndex = pos;
      }
    }

    // Trim excess
    IMEUtil.tripSuggestions(mSuggestions, prefMaxSuggestions, mStringPool);
  }

  @Override
  public int getLastValidSuggestionIndex() {
    return mCorrectSuggestionIndex;
  }

  @Override
  public boolean isValidWord(CharSequence word) {
    if (word == null || word.length() == 0) return false;
    // Check our vocabulary first, then fall back to ASK's dictionaries
    // (user dict, contacts, etc.)
    if (mVocabulary.isOpen() && mVocabulary.isValidWord(word.toString())) return true;
    return mSuggestionsProvider.isValidWord(word);
  }

  @Override
  public boolean addWordToUserDictionary(String word) {
    return mSuggestionsProvider.addWordToUserDictionary(word);
  }

  @Override
  public void removeWordFromUserDictionary(String word) {
    mSuggestionsProvider.removeWordFromUserDictionary(word);
  }

  @Override
  public void setTagsSearcher(@NonNull TagsExtractor extractor) {
    mTagsSearcher = extractor;
  }

  @Override
  public boolean tryToLearnNewWord(CharSequence newWord, AdditionType additionType) {
    return mSuggestionsProvider.tryToLearnNewWord(newWord, additionType.getFrequencyDelta());
  }

  @Override
  public void setIncognitoMode(boolean incognitoMode) {
    mSuggestionsProvider.setIncognitoMode(incognitoMode);
  }

  @Override
  public boolean isIncognitoMode() {
    return mSuggestionsProvider.isIncognitoMode();
  }

  @Override
  public void destroy() {
    closeDictionaries();
    mVocabulary.close();
    mSuggestionsProvider.destroy();
  }

  private void collectGarbage() {
    int poolSize = mStringPool.size();
    int garbageSize = mSuggestions.size();
    while (poolSize < mPrefMaxSuggestions && garbageSize > 0) {
      CharSequence garbage = mSuggestions.get(garbageSize - 1);
      if (garbage instanceof StringBuilder) {
        mStringPool.add(garbage);
        poolSize++;
      }
      garbageSize--;
    }
    mSuggestions.clear();
  }

  @NonNull
  private StringBuilder getStringBuilderFromPool(
      String word, boolean isFirstCharCapitalized, boolean isAllUpperCase) {
    int poolSize = mStringPool.size();
    StringBuilder sb =
        poolSize > 0
            ? (StringBuilder) mStringPool.remove(poolSize - 1)
            : new StringBuilder(Dictionary.MAX_WORD_LENGTH);
    sb.setLength(0);
    if (isAllUpperCase) {
      sb.append(word.toUpperCase(mLocale));
    } else if (isFirstCharCapitalized && word.length() > 0) {
      sb.append(Character.toUpperCase(word.charAt(0)));
      if (word.length() > 1) {
        sb.append(word, 1, word.length());
      }
    } else {
      sb.append(word);
    }
    return sb;
  }
}
