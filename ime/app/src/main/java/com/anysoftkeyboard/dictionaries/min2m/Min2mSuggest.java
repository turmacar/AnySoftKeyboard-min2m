package com.anysoftkeyboard.dictionaries.min2m;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Alternative suggestion engine that uses a large open-source vocabulary database (~300K words) with
 * full-range integer frequencies for scoring, replacing ASK's dictionary-based fuzzy matching.
 *
 * <p>Phase A: vocabulary-based prefix matching with frequency ranking. Phase B (future): Bayesian
 * spatial disambiguation using touch coordinates. Phase C (future): n-gram context scoring.
 *
 * <p>Delegates user dictionary, contacts, abbreviations, and auto-text to ASK's existing {@link
 * SuggestionsProvider}.
 */
public class Min2mSuggest implements Suggest {
  private static final String TAG = "Min2mSuggest";

  // Frequency tiers matching SuggestImpl conventions so the candidate view
  // treats typed-word, valid-typed, and corrections the same way.
  private static final int TYPED_WORD_FREQUENCY = Integer.MAX_VALUE;
  private static final int VALID_TYPED_WORD_FREQUENCY = Integer.MAX_VALUE - 25;

  @NonNull private final SuggestionsProvider mSuggestionsProvider;
  @NonNull private final Min2mVocabulary mVocabulary;
  @NonNull private final SpatialScorer mSpatialScorer;
  @NonNull private final BayesianCandidateRanker mRanker;
  @NonNull private final Context mContext;

  private final List<CharSequence> mSuggestions = new ArrayList<>();
  private final List<CharSequence> mNextSuggestions = new ArrayList<>();
  private final List<CharSequence> mStringPool = new ArrayList<>();
  private int[] mPriorities = new int[12];

  @NonNull private final Locale mLocale = Locale.getDefault();
  @NonNull private TagsExtractor mTagsSearcher = TagsExtractorImpl.NO_OP;

  private int mPrefMaxSuggestions = 12;
  private int mCorrectSuggestionIndex = -1;
  private boolean mEnabledSuggestions;
  private int mCommonalityMaxLengthDiff = 1;
  private int mCommonalityMaxDistance = 1;

  public Min2mSuggest(@NonNull Context context) {
    mContext = context;
    mSuggestionsProvider = new SuggestionsProvider(context);
    mVocabulary = new Min2mVocabulary();
    mSpatialScorer = new SpatialScorer();
    mRanker = new BayesianCandidateRanker();
    setMaxSuggestions(mPrefMaxSuggestions);

    // Load vocabulary on a background thread to avoid StrictMode disk-read
    // violations on the main thread. Suggestions gracefully degrade (empty)
    // until loading completes (~1s).
    new Thread(() -> {
      mVocabulary.open(context);
      if (mVocabulary.isOpen()) {
        mRanker.setMaxFrequency(mVocabulary.getMaxFrequency());
      }
      Logger.d(TAG, "Vocabulary background load complete");
    }, "min2m-vocab-load").start();
  }

  @VisibleForTesting
  public Min2mSuggest(@NonNull SuggestionsProvider provider, @NonNull Min2mVocabulary vocabulary) {
    mContext = null;
    mSuggestionsProvider = provider;
    mVocabulary = vocabulary;
    mSpatialScorer = new SpatialScorer();
    mRanker = new BayesianCandidateRanker();
    setMaxSuggestions(mPrefMaxSuggestions);
  }

  /**
   * Registers the current keyboard's key geometry for spatial scoring.
   * Call this when the keyboard layout changes.
   */
  public void registerKeyboard(@NonNull java.util.List<Keyboard.Key> keys) {
    mSpatialScorer.registerKeyboard(keys);
    Logger.d(TAG, "Registered keyboard with %d keys for spatial scoring", keys.size());
  }

  @Override
  public void setCorrectionMode(
      boolean enabledSuggestions, int maxLengthDiff, int maxDistance, boolean splitWords) {
    mEnabledSuggestions = enabledSuggestions;
    mCommonalityMaxLengthDiff = maxLengthDiff;
    mCommonalityMaxDistance = maxDistance;
    // splitWords not used in min2m engine (future: spatial scoring handles this better)
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
      }
    }
    return mNextSuggestions;
  }

  @Override
  public List<CharSequence> getSuggestions(WordComposer wordComposer) {
    if (!mEnabledSuggestions) return Collections.emptyList();

    mCorrectSuggestionIndex = -1;
    final boolean isFirstCharCapitalized = wordComposer.isFirstCharCapitalized();
    final boolean isAllUpperCase = wordComposer.isAllUpperCase();
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
    mSuggestions.add(0, typedOriginalWord);
    mPriorities[0] = TYPED_WORD_FREQUENCY;

    final boolean hasTouchData =
        wordComposer.hasTouchCoordinates() && mSpatialScorer.hasKeyboard();
    final int touchCount = wordComposer.codePointCount();
    int typedWordSpatialPriority = -1;

    if (mVocabulary.isOpen()) {
      // --- Step 1: Generate candidate pool ---
      // Find all plausible first characters within spatial range of first touch.
      java.util.Set<Character> firstCharCandidates = new java.util.HashSet<>();
      if (hasTouchData && touchCount >= 1) {
        float touchX0 = wordComposer.getTouchX(0);
        float touchY0 = wordComposer.getTouchY(0);
        float threshold = mSpatialScorer.getAvgKeyWidth() * 4f;
        float thresholdSq = threshold * threshold;

        for (int ch = 'a'; ch <= 'z'; ch++) {
          float[] center = mSpatialScorer.getKeyCenter(ch);
          if (center != null) {
            float dx = touchX0 - center[0];
            float dy = touchY0 - center[1];
            if (dx * dx + dy * dy <= thresholdSq) {
              firstCharCandidates.add((char) ch);
            }
          }
        }
      }

      List<Min2mVocabulary.CandidateWord> candidates = mVocabulary.getSpatialCandidates(
          firstCharCandidates, touchCount, lowerOriginalWord, mPrefMaxSuggestions * 3);

      // --- Step 2: Extract touch coordinates ---
      float[] touchXs = new float[touchCount];
      float[] touchYs = new float[touchCount];
      if (hasTouchData) {
        for (int i = 0; i < touchCount; i++) {
          touchXs[i] = wordComposer.getTouchX(i);
          touchYs[i] = wordComposer.getTouchY(i);
        }
      }

      // Build n-gram context set for boosting
      java.util.Set<String> bigramNextWords = new java.util.HashSet<>();
      for (CharSequence nw : mNextSuggestions) {
        bigramNextWords.add(nw.toString().toLowerCase(mLocale));
      }

      // --- Step 3: Score every candidate ---
      // Spatial scoring is always primary. All candidates (including the typed
      // word) are scored uniformly by: α·ln(freq/max) + β·spatialLogP.
      // The key detector's output is just an approximation - the spatial scorer
      // is the real decoder, like Minuum.
      for (Min2mVocabulary.CandidateWord candidate : candidates) {
        // Trigram pre-filter: skip candidates with implausible char sequences
        if (!candidate.text.equalsIgnoreCase(lowerOriginalWord)
            && !mVocabulary.isPlausibleWord(candidate.text)) {
          continue;
        }

        float spatialLogP = hasTouchData
            ? mSpatialScorer.scoreWord(candidate.text, touchXs, touchYs, touchCount)
            : 0f;
        int candidateLen = candidate.text.codePointCount(0, candidate.text.length());
        float bayesianScore = mRanker.score(candidate.frequency, spatialLogP, candidateLen);

        // N-gram context boost
        if (bigramNextWords.contains(candidate.text)) {
          bayesianScore += 3.0f;
        }

        int scaledFreq = BayesianCandidateRanker.toIntPriority(bayesianScore);

        // Track the typed word's spatial score for auto-correction decision
        if (candidate.text.equalsIgnoreCase(lowerOriginalWord)) {
          typedWordSpatialPriority = scaledFreq;
        }

        StringBuilder sb = getStringBuilderFromPool(
            candidate.text, isFirstCharCapitalized, isAllUpperCase);
        insertSuggestion(sb, scaledFreq);
      }
    }

    // User-learned words and contacts
    mSuggestionsProvider.getAbbreviations(wordComposer, mAskBridgeCallback);
    mSuggestionsProvider.getAutoText(wordComposer, mAskBridgeCallback);
    mSuggestionsProvider.getUserAndContactsSuggestions(wordComposer, mAskBridgeCallback);

    // --- Step 4: Auto-correction ---
    // Auto-correct to position 1 only if it scored higher than the typed word.
    // If the typed word IS the spatial winner (e.g., user typed "see" correctly
    // and it has the best spatial score), don't auto-correct away from it.
    if (mSuggestions.size() > 1 && mPriorities[1] > 0) {
      if (typedWordSpatialPriority < 0 || mPriorities[1] > typedWordSpatialPriority) {
        // Position 1 scored better than the typed word (or typed word isn't
        // in vocabulary at all) - auto-correct to the spatial winner.
        mCorrectSuggestionIndex = 1;
      }
      // else: typed word IS the spatial winner - no auto-correction needed.
    }

    IMEUtil.removeDupes(mSuggestions, mStringPool);

    // Diagnostic logging
    if (mSuggestions.size() > 1) {
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
