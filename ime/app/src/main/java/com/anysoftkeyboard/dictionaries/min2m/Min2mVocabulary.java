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
import java.util.List;

/**
 * Read-only wrapper around the vocabulary SQLite database (vocab.db). Provides prefix search,
 * frequency lookup, and word validation against a ~300K word corpus with full-range integer
 * frequencies.
 */
public class Min2mVocabulary {
  private static final String TAG = "Min2mVocabulary";
  private static final String ASSET_PATH = "disambigdata/en/vocab.db";
  private static final String DB_NAME = "min2m_vocab.db";
  private static final int DB_VERSION = 1;

  @Nullable private SQLiteDatabase mDb;
  private boolean mIsOpen;

  /** A word with its frequency data from the vocabulary database. */
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

  /**
   * Opens the vocabulary database. Copies the asset to the app's database directory on first use.
   */
  public void open(@NonNull Context context) {
    if (mIsOpen) return;

    try {
      File dbFile = context.getDatabasePath(DB_NAME);
      if (!dbFile.exists() || shouldRecopy(context, dbFile)) {
        copyAssetToFile(context, dbFile);
      }
      mDb = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
      mIsOpen = true;
      Logger.d(TAG, "Vocabulary database opened: %s", dbFile.getPath());
    } catch (IOException e) {
      Logger.e(TAG, "Failed to open vocabulary database: %s", e.getMessage());
    }
  }

  /** Closes the database. */
  public void close() {
    if (mDb != null) {
      mDb.close();
      mDb = null;
    }
    mIsOpen = false;
  }

  public boolean isOpen() {
    return mIsOpen && mDb != null;
  }

  /**
   * Returns words matching the given prefix, ordered by frequency descending.
   *
   * @param prefix the prefix to match (case-insensitive, will be lowercased)
   * @param limit maximum number of results
   * @return list of matching words, highest frequency first
   */
  @NonNull
  public List<CandidateWord> getPrefixMatches(@NonNull String prefix, int limit) {
    List<CandidateWord> results = new ArrayList<>();
    if (!mIsOpen || mDb == null || prefix.isEmpty()) return results;

    String lowerPrefix = prefix.toLowerCase();
    // Use range query instead of LIKE for index efficiency:
    // text >= 'prefix' AND text < 'prefiy' (increment last char)
    String upperBound = incrementLastChar(lowerPrefix);

    try (Cursor c =
        mDb.rawQuery(
            "SELECT word_id, text, frequency, zipf FROM vocab "
                + "WHERE text >= ? AND text < ? "
                + "ORDER BY frequency DESC LIMIT ?",
            new String[] {lowerPrefix, upperBound, String.valueOf(limit)})) {
      while (c.moveToNext()) {
        results.add(new CandidateWord(c.getInt(0), c.getString(1), c.getInt(2), c.getFloat(3)));
      }
    }
    return results;
  }

  /**
   * Returns the frequency for an exact word match, or -1 if not found.
   *
   * @param word the word to look up (case-insensitive)
   */
  public int getFrequency(@NonNull String word) {
    if (!mIsOpen || mDb == null) return -1;

    try (Cursor c =
        mDb.rawQuery(
            "SELECT frequency FROM vocab WHERE text = ?",
            new String[] {word.toLowerCase()})) {
      if (c.moveToFirst()) {
        return c.getInt(0);
      }
    }
    return -1;
  }

  /**
   * Checks if a word exists in the vocabulary.
   *
   * @param word the word to check (case-insensitive)
   */
  public boolean isValidWord(@NonNull String word) {
    return getFrequency(word) >= 0;
  }

  /** Returns the maximum frequency in the database (cached after first call). */
  private int mMaxFrequency = -1;

  public int getMaxFrequency() {
    if (mMaxFrequency > 0) return mMaxFrequency;
    if (!mIsOpen || mDb == null) return 1;

    try (Cursor c = mDb.rawQuery("SELECT MAX(frequency) FROM vocab", null)) {
      if (c.moveToFirst()) {
        mMaxFrequency = c.getInt(0);
      }
    }
    return mMaxFrequency > 0 ? mMaxFrequency : 1;
  }

  /**
   * Increments the last character of a string to create an exclusive upper bound for range queries.
   * "hel" → "hem", enabling text >= 'hel' AND text < 'hem'.
   */
  @NonNull
  private static String incrementLastChar(@NonNull String s) {
    if (s.isEmpty()) return "\uffff";
    char last = s.charAt(s.length() - 1);
    return s.substring(0, s.length() - 1) + (char) (last + 1);
  }

  /**
   * Check if the asset has been updated (e.g., app upgrade with new vocab.db). Compares file
   * modification time with a stored version marker.
   */
  private boolean shouldRecopy(@NonNull Context context, @NonNull File dbFile) {
    File versionFile = new File(dbFile.getParent(), DB_NAME + ".v" + DB_VERSION);
    return !versionFile.exists();
  }

  /** Copy the vocab.db asset to the app's database directory. */
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

    // Write version marker so we know when to re-copy on upgrade
    File versionFile = new File(destFile.getParent(), DB_NAME + ".v" + DB_VERSION);
    versionFile.createNewFile();

    Logger.d(TAG, "Copied vocabulary database to %s", destFile.getPath());
  }
}
