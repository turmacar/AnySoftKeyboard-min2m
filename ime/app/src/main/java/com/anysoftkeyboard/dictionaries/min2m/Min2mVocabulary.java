package com.anysoftkeyboard.dictionaries.min2m;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.anysoftkeyboard.base.utils.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory vocabulary for suggestion generation. Loads all words and bigrams from vocab.db at
 * startup, then serves all lookups from memory - zero disk I/O during typing.
 *
 * <p>Memory footprint: ~12 MB for 307K words + 116K bigram entries.
 */
public class Min2mVocabulary {
  private static final String TAG = "Min2mVocabulary";
  private static final String ASSET_PATH = "disambigdata/en/vocab.db";
  private static final String DB_NAME = "min2m_vocab.db";
  private static final int DB_VERSION = 2;

  /** A word with its frequency data. */
  public static class CandidateWord {
    public final int wordId;
    @NonNull public final String text;
    public final int frequency;
    public final float zipf;

    public CandidateWord(int wordId, @NonNull String text, int frequency, float zipf) {
      this.wordId = wordId;
      this.text = text;
      this.frequency = frequency;
      this.zipf = zipf;
    }
  }

  // --- In-memory data structures ---

  /** All words keyed by text (lowercase). For exact lookup and validation. */
  private final Map<String, CandidateWord> mWordMap = new HashMap<>();

  /** Sorted map for prefix range queries. Key = lowercase word text. */
  private final NavigableMap<String, CandidateWord> mSortedWords = new TreeMap<>();

  /**
   * Words grouped by length, then by first character. For spatial candidate search.
   * Key: word length -> first char -> list of words sorted by frequency desc.
   */
  private final Map<Integer, Map<Character, List<CandidateWord>>> mByLengthAndFirstChar =
      new HashMap<>();

  /**
   * Top-frequency words grouped by length. For supplementing kd-tree results with
   * common words that might be missed by spatial search (e.g., "that" when the first
   * touch lands on a neighboring key). Sorted by frequency descending.
   */
  private final Map<Integer, List<CandidateWord>> mTopByLength = new HashMap<>();

  /** Bigram next-word predictions. Key: word -> list of predicted next words (ordered by rank). */
  private final Map<String, List<String>> mBigrams = new HashMap<>();

  /**
   * Character trigram frequency table. Key: trigram string (3 chars), Value: count of vocab words
   * containing that trigram. Built at load time from the vocabulary. Used as a fast pre-filter
   * to reject implausible candidates before expensive spatial scoring.
   *
   * <p>Words with word-boundary markers: "^th", "the", "he$" for "the".
   * Trigrams appearing in fewer than {@link #TRIGRAM_MIN_COUNT} words are considered rare.
   */
  private final Map<String, Integer> mTrigramCounts = new HashMap<>();

  /** Minimum trigram count threshold. Words containing trigrams below this are unlikely. */
  private static final int TRIGRAM_MIN_COUNT = 3;

  private int mMaxFrequency = 1;
  private boolean mIsOpen;

  /**
   * Opens the vocabulary database, loads all data into memory, then closes the database file.
   * The DB file is only touched during this call - all subsequent lookups are in-memory.
   */
  public void open(@NonNull Context context) {
    if (mIsOpen) return;

    try {
      File dbFile = context.getDatabasePath(DB_NAME);
      if (!dbFile.exists() || shouldRecopy(context, dbFile)) {
        copyAssetToFile(context, dbFile);
      }

      long t0 = System.currentTimeMillis();
      SQLiteDatabase db =
          SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);

      loadVocabulary(db);
      buildTrigramTable();
      loadBigrams(db);

      db.close();
      mIsOpen = true;

      long elapsed = System.currentTimeMillis() - t0;
      Logger.d(
          TAG,
          "Vocabulary loaded into memory: %d words, %d bigram entries, %d trigrams, max freq %d, in %dms",
          mWordMap.size(),
          mBigrams.size(),
          mTrigramCounts.size(),
          mMaxFrequency,
          elapsed);
    } catch (IOException e) {
      Logger.e(TAG, "Failed to open vocabulary database: %s", e.getMessage());
    }
  }

  private void loadVocabulary(@NonNull SQLiteDatabase db) {
    try (Cursor c = db.rawQuery("SELECT word_id, text, frequency, zipf FROM vocab", null)) {
      while (c.moveToNext()) {
        int wordId = c.getInt(0);
        String text = c.getString(1);
        int frequency = c.getInt(2);
        float zipf = c.getFloat(3);

        CandidateWord word = new CandidateWord(wordId, text, frequency, zipf);
        mWordMap.put(text, word);
        mSortedWords.put(text, word);

        if (frequency > mMaxFrequency) {
          mMaxFrequency = frequency;
        }

        // Index by length + first char
        int len = text.length();
        char firstChar = text.charAt(0);
        mByLengthAndFirstChar
            .computeIfAbsent(len, k -> new HashMap<>())
            .computeIfAbsent(firstChar, k -> new ArrayList<>())
            .add(word);
      }
    }

    // Sort each length+firstChar bucket by frequency descending
    for (Map<Character, List<CandidateWord>> byChar : mByLengthAndFirstChar.values()) {
      for (List<CandidateWord> words : byChar.values()) {
        words.sort((a, b) -> Integer.compare(b.frequency, a.frequency));
      }
    }

    // Build top-by-length index: merge all first-char buckets per length,
    // sort by frequency, keep top 50. These common words supplement kd-tree
    // results to ensure high-frequency words are always candidates.
    for (Map.Entry<Integer, Map<Character, List<CandidateWord>>> entry :
        mByLengthAndFirstChar.entrySet()) {
      List<CandidateWord> allAtLength = new ArrayList<>();
      for (List<CandidateWord> bucket : entry.getValue().values()) {
        allAtLength.addAll(bucket);
      }
      allAtLength.sort((a, b) -> Integer.compare(b.frequency, a.frequency));
      int cap = Math.min(allAtLength.size(), 50);
      mTopByLength.put(entry.getKey(), new ArrayList<>(allAtLength.subList(0, cap)));
    }
  }

  /**
   * Builds the character trigram frequency table from the loaded vocabulary.
   * Each word is padded with '^' (start) and '$' (end) markers, then decomposed
   * into overlapping 3-character windows. The count records how many distinct
   * words contain each trigram.
   *
   * <p>Example: "the" → trigrams: "^th", "the", "he$"
   */
  private void buildTrigramTable() {
    mTrigramCounts.clear();
    for (String word : mWordMap.keySet()) {
      // Pad with boundary markers
      String padded = "^" + word + "$";
      // Use a set to count each trigram only once per word
      Set<String> wordTrigrams = new HashSet<>();
      for (int i = 0; i <= padded.length() - 3; i++) {
        wordTrigrams.add(padded.substring(i, i + 3));
      }
      for (String trigram : wordTrigrams) {
        mTrigramCounts.merge(trigram, 1, Integer::sum);
      }
    }
  }

  /**
   * Checks if a candidate word is plausible based on its character trigrams.
   * A word is implausible if it contains any trigram that appears in fewer than
   * {@link #TRIGRAM_MIN_COUNT} vocabulary words - meaning that character sequence
   * is extremely rare in English.
   *
   * <p>This is a fast pre-filter (~1μs per word) to reject garbage candidates
   * before the more expensive spatial scoring (~10μs per word).
   *
   * @param word the candidate word (lowercase)
   * @return true if the word's trigrams are all plausible
   */
  public boolean isPlausibleWord(@NonNull String word) {
    if (mTrigramCounts.isEmpty()) return true; // no trigram data loaded
    if (word.length() < 3) return true; // too short for trigram filtering

    String padded = "^" + word + "$";
    for (int i = 0; i <= padded.length() - 3; i++) {
      String trigram = padded.substring(i, i + 3);
      Integer count = mTrigramCounts.get(trigram);
      if (count == null || count < TRIGRAM_MIN_COUNT) {
        return false;
      }
    }
    return true;
  }

  private void loadBigrams(@NonNull SQLiteDatabase db) {
    try (Cursor c = db.rawQuery("SELECT word, next_word FROM bigrams ORDER BY word, rank", null)) {
      while (c.moveToNext()) {
        String word = c.getString(0);
        String nextWord = c.getString(1);
        mBigrams.computeIfAbsent(word, k -> new ArrayList<>()).add(nextWord);
      }
    } catch (android.database.sqlite.SQLiteException e) {
      Logger.w(TAG, "bigrams table not available: %s", e.getMessage());
    }
  }

  public void close() {
    mWordMap.clear();
    mSortedWords.clear();
    mByLengthAndFirstChar.clear();
    mTopByLength.clear();
    mBigrams.clear();
    mTrigramCounts.clear();
    mMaxFrequency = 1;
    mIsOpen = false;
  }

  public boolean isOpen() {
    return mIsOpen;
  }

  // --- Query methods (all in-memory, no disk I/O) ---

  /**
   * Returns the most frequent words of a given length, regardless of first character.
   * Used to supplement kd-tree spatial results with common words that might be missed
   * when the first touch lands on a neighboring key (e.g., "that" when 't' detected as 'g').
   *
   * @param length word length
   * @param limit maximum words to return
   */
  @NonNull
  public List<CandidateWord> getTopByLength(int length, int limit) {
    if (!mIsOpen) return Collections.emptyList();
    List<CandidateWord> top = mTopByLength.get(length);
    if (top == null) return Collections.emptyList();
    if (top.size() <= limit) return top;
    return top.subList(0, limit);
  }

  /**
   * Returns words matching the given prefix, ordered by frequency descending.
   *
   * @param prefix the prefix to match (lowercase)
   * @param limit maximum number of results
   */
  @NonNull
  public List<CandidateWord> getPrefixMatches(@NonNull String prefix, int limit) {
    if (!mIsOpen || prefix.isEmpty()) return Collections.emptyList();

    String lower = prefix.toLowerCase();
    String upperBound = incrementLastChar(lower);

    // NavigableMap.subMap gives us all keys in [lower, upperBound)
    List<CandidateWord> results = new ArrayList<>(mSortedWords.subMap(lower, upperBound).values());
    // Sort by frequency descending and limit
    results.sort((a, b) -> Integer.compare(b.frequency, a.frequency));
    if (results.size() > limit) {
      return new ArrayList<>(results.subList(0, limit));
    }
    return results;
  }

  /** Returns the frequency for an exact word match, or -1 if not found. */
  public int getFrequency(@NonNull String word) {
    if (!mIsOpen) return -1;
    CandidateWord cw = mWordMap.get(word.toLowerCase());
    return cw != null ? cw.frequency : -1;
  }

  /** Checks if a word exists in the vocabulary. */
  public boolean isValidWord(@NonNull String word) {
    return mIsOpen && mWordMap.containsKey(word.toLowerCase());
  }

  /** Returns all words in the vocabulary. For spatial index construction. */
  @NonNull
  public java.util.Collection<CandidateWord> getAllWords() {
    return mWordMap.values();
  }

  /** Returns the top next-word predictions from bigram data. */
  @NonNull
  public List<String> getNextWords(@NonNull String word) {
    if (!mIsOpen) return Collections.emptyList();
    List<String> results = mBigrams.get(word.toLowerCase());
    return results != null ? results : Collections.emptyList();
  }

  /**
   * Returns candidate words for spatial disambiguation. Combines:
   * 1. Prefix completions of the typed word
   * 2. Same-length words starting with any nearby first character
   * 3. +/-1 length words for edit-distance correction (missed/extra keystroke)
   * 4. Apostrophe-containing words matching the prefix (e.g., "I'm", "don't")
   *
   * All lookups are in-memory - no disk I/O.
   */
  @NonNull
  public List<CandidateWord> getSpatialCandidates(
      @NonNull java.util.Set<Character> firstChars,
      int wordLength,
      @NonNull String typedPrefix,
      int limit) {
    if (!mIsOpen) return Collections.emptyList();

    java.util.Set<String> seen = new java.util.HashSet<>();
    List<CandidateWord> results = new ArrayList<>();

    // Part 1: Prefix completions of what was actually typed
    if (!typedPrefix.isEmpty()) {
      for (CandidateWord word : getPrefixMatches(typedPrefix, limit)) {
        if (seen.add(word.text)) {
          results.add(word);
        }
      }
    }

    // Part 2: Same-length words starting with any nearby first character
    // Part 3: +/-1 length words for edit-distance correction
    if (wordLength >= 1 && !firstChars.isEmpty()) {
      int perCharLimit = 50;
      int editDistPerCharLimit = 20;

      for (int len = wordLength - 1; len <= wordLength + 1; len++) {
        if (len < 1) continue;
        Map<Character, List<CandidateWord>> byChar = mByLengthAndFirstChar.get(len);
        if (byChar == null) continue;
        int charLimit = (len == wordLength) ? perCharLimit : editDistPerCharLimit;

        for (char fc : firstChars) {
          List<CandidateWord> bucket = byChar.get(fc);
          if (bucket == null) continue;
          int added = 0;
          for (CandidateWord word : bucket) {
            if (added >= charLimit) break;
            if (seen.add(word.text)) {
              results.add(word);
              added++;
            }
          }
        }
      }

      // Part 4: Apostrophe-containing words.
      // Words like "I'm", "don't", "let's" are indexed by their full length
      // (including apostrophe) and first letter. Also search lengths that account
      // for the apostrophe: typed length + 2 (apostrophe + one more char, e.g.,
      // typing "im" should find "i'm", typing "dont" should find "don't").
      for (int len = wordLength + 1; len <= wordLength + 2; len++) {
        Map<Character, List<CandidateWord>> byChar = mByLengthAndFirstChar.get(len);
        if (byChar == null) continue;
        for (char fc : firstChars) {
          List<CandidateWord> bucket = byChar.get(fc);
          if (bucket == null) continue;
          int added = 0;
          for (CandidateWord word : bucket) {
            if (added >= 10) break;
            if (word.text.indexOf('\'') >= 0 && seen.add(word.text)) {
              results.add(word);
              added++;
            }
          }
        }
      }
    }

    return results;
  }

  public int getMaxFrequency() {
    return mMaxFrequency;
  }

  @NonNull
  private static String incrementLastChar(@NonNull String s) {
    if (s.isEmpty()) return "\uffff";
    char last = s.charAt(s.length() - 1);
    return s.substring(0, s.length() - 1) + (char) (last + 1);
  }

  private boolean shouldRecopy(@NonNull Context context, @NonNull File dbFile) {
    File versionFile = new File(dbFile.getParent(), DB_NAME + ".v" + DB_VERSION);
    return !versionFile.exists();
  }

  private void copyAssetToFile(@NonNull Context context, @NonNull File destFile)
      throws IOException {
    File parentDir = destFile.getParentFile();
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs();
    }

    try (InputStream in = context.getAssets().open(ASSET_PATH);
        OutputStream out = new FileOutputStream(destFile)) {
      byte[] buffer = new byte[8192];
      int length;
      while ((length = in.read(buffer)) > 0) {
        out.write(buffer, 0, length);
      }
    }

    File versionFile = new File(destFile.getParent(), DB_NAME + ".v" + DB_VERSION);
    versionFile.createNewFile();

    Logger.d(TAG, "Copied vocabulary database to %s", destFile.getPath());
  }
}
