package com.anysoftkeyboard.dictionaries.min2m;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.anysoftkeyboard.base.utils.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * KD-tree spatial index for candidate word lookup. Maps each vocabulary word to a position vector
 * based on its characters' key positions, then builds one kd-tree per word length for O(log n)
 * nearest-neighbor queries.
 *
 * <p>Architecture follows Minuum's {@code wsKdTreeQuery}: words are represented as points in a
 * multi-dimensional space (one dimension per character position), and spatially nearby words in this
 * space are the most likely intended words for a given touch sequence.
 *
 * <p>For a 1D keyboard, dimensions = word length (x-coordinate per character). For a 2D keyboard,
 * dimensions = 2 × word length (x,y per character). Trees are rebuilt when the keyboard layout
 * changes.
 */
public class SpatialIndex {
  private static final String TAG = "SpatialIndex";

  /** Maximum word length to index. Words longer than this use fallback brute-force.
   * Set to 20 to cover all practical word lengths. Per-length buckets for long words
   * are small (e.g., length 14 = 2,672 words, length 18 = 166 words), so even
   * degraded kd-tree queries at high dimensions complete quickly. The visit limit
   * ({@link #MAX_NODES_VISITED}) caps worst-case time regardless of dimensionality.
   * This matches Minuum's approach: kd-trees for all lengths, small buckets at
   * high lengths make dimension degradation irrelevant. */
  private static final int MAX_INDEXED_LENGTH = 20;

  /** Number of nearest neighbors to return per query. */
  private static final int DEFAULT_K = 100;

  /**
   * Whether the keyboard is 1D (all keys on one row, y-coordinates nearly identical). In 1D mode,
   * position vectors use only x-coordinates (N dimensions for N-char word). In 2D mode, position
   * vectors use x,y pairs (2N dimensions).
   */
  private volatile boolean mIs1D;

  /** One kd-tree per word length (index = word length, 1-based). Null if no words at that length. */
  private volatile KdNode[] mTrees;

  /** Parallel array: all indexed entries per word length, same order as tree construction. */
  private volatile IndexEntry[][] mEntries;

  /** Key centers from the current keyboard (lowercase char code → {x, y}). */
  private volatile Map<Integer, float[]> mKeyCenters;

  private volatile boolean mIsBuilt;

  /** An indexed word with its position vector. */
  static class IndexEntry {
    @NonNull final Min2mVocabulary.CandidateWord word;
    @NonNull final float[] position; // position vector in kd-tree space

    IndexEntry(@NonNull Min2mVocabulary.CandidateWord word, @NonNull float[] position) {
      this.word = word;
      this.position = position;
    }
  }

  /** KD-tree node. Each node splits the space along one dimension at the median. */
  static class KdNode {
    /** Index into the entries array for this node's point. */
    int entryIndex;
    /** Dimension this node splits on. */
    int splitDim;
    /** Split value (the point's coordinate in splitDim). */
    float splitValue;
    @Nullable KdNode left;
    @Nullable KdNode right;
  }

  /** Priority queue entry for k-nearest-neighbor search. */
  private static class KnnCandidate implements Comparable<KnnCandidate> {
    final int entryIndex;
    final float distSq;

    KnnCandidate(int entryIndex, float distSq) {
      this.entryIndex = entryIndex;
      this.distSq = distSq;
    }

    @Override
    public int compareTo(KnnCandidate other) {
      // Max-heap: largest distance first (so we can evict the farthest)
      return Float.compare(other.distSq, this.distSq);
    }
  }

  /** Guard against concurrent/redundant builds. */
  private volatile boolean mBuilding;

  /**
   * Builds the spatial index for all vocabulary words using the given keyboard geometry.
   * If a build is already in progress, this call is skipped (the caller can retry later
   * or the in-progress build will publish results shortly).
   *
   * @param vocabulary the loaded vocabulary
   * @param keyCenters map from lowercase char code to {x, y} key center
   * @param is1D whether the keyboard is a 1D compact layout
   */
  public void build(
      @NonNull Min2mVocabulary vocabulary,
      @NonNull Map<Integer, float[]> keyCenters,
      boolean is1D) {
    if (mBuilding) {
      Logger.d(TAG, "Skipping build — already in progress");
      return;
    }
    mBuilding = true;

    try {
      buildInternal(vocabulary, keyCenters, is1D);
    } finally {
      mBuilding = false;
    }
  }

  private void buildInternal(
      @NonNull Min2mVocabulary vocabulary,
      @NonNull Map<Integer, float[]> keyCenters,
      boolean is1D) {
    // Build into local variables so the main thread always sees a consistent
    // snapshot. Multiple background threads may race (keyboard changes rapidly
    // during startup); this prevents the main thread from seeing mismatched
    // mIs1D / mTrees / mEntries.
    long t0 = System.currentTimeMillis();

    // Group words by their indexable length (letter-only length for apostrophe words)
    @SuppressWarnings("unchecked")
    List<IndexEntry>[] byLength = (List<IndexEntry>[]) new List<?>[MAX_INDEXED_LENGTH + 1];
    for (int i = 0; i <= MAX_INDEXED_LENGTH; i++) {
      byLength[i] = new ArrayList<>();
    }

    for (Min2mVocabulary.CandidateWord word : vocabulary.getAllWords()) {
      int letterLen = letterLength(word.text);
      if (letterLen < 1 || letterLen > MAX_INDEXED_LENGTH) continue;

      float[] posVec = computePositionVector(word.text, letterLen, keyCenters, is1D);
      if (posVec != null) {
        byLength[letterLen].add(new IndexEntry(word, posVec));
      }
    }

    // Build one kd-tree per word length into local arrays
    KdNode[] newTrees = new KdNode[MAX_INDEXED_LENGTH + 1];
    IndexEntry[][] newEntries = new IndexEntry[MAX_INDEXED_LENGTH + 1][];

    int totalIndexed = 0;
    for (int len = 1; len <= MAX_INDEXED_LENGTH; len++) {
      List<IndexEntry> entries = byLength[len];
      if (entries.isEmpty()) {
        newTrees[len] = null;
        newEntries[len] = null;
        continue;
      }

      newEntries[len] = entries.toArray(new IndexEntry[0]);
      int dims = is1D ? len : len * 2;
      int[] indices = new int[entries.size()];
      for (int i = 0; i < indices.length; i++) indices[i] = i;

      newTrees[len] = buildTree(newEntries[len], indices, 0, indices.length - 1, 0, dims);
      totalIndexed += entries.size();
    }

    // Atomic publish: swap all fields together so the main thread
    // always sees a consistent {is1D, trees, entries} triple.
    mKeyCenters = keyCenters;
    mIs1D = is1D;
    mEntries = newEntries;
    mTrees = newTrees;
    mIsBuilt = true;

    // Estimate memory: each IndexEntry has a CandidateWord ref + float[] position vector.
    // Position vector: 4 bytes × dims. KdNode: ~40 bytes (3 fields + 2 refs).
    // Total ≈ (posVecBytes + nodeOverhead) × totalIndexed
    long estimatedBytes = 0;
    for (int len = 1; len <= MAX_INDEXED_LENGTH; len++) {
      if (newEntries[len] == null) continue;
      int dims = is1D ? len : len * 2;
      // Per entry: float[dims] (16 + 4*dims bytes) + IndexEntry object (~32 bytes)
      // Per node: KdNode object (~48 bytes)
      estimatedBytes += (long) newEntries[len].length * (48 + 32 + 16 + 4 * dims);
    }

    long elapsed = System.currentTimeMillis() - t0;
    Logger.d(
        TAG,
        "Spatial index built: %d words indexed across %d length buckets in %dms (mode=%s, ~%.1fMB)",
        totalIndexed,
        MAX_INDEXED_LENGTH,
        elapsed,
        is1D ? "1D" : "2D",
        estimatedBytes / (1024.0 * 1024.0));
  }

  public boolean isBuilt() {
    return mIsBuilt;
  }

  /**
   * Queries the spatial index for the k nearest candidate words to the given touch sequence.
   * Searches the tree for the exact touch count length plus +/-1 for edit distance coverage.
   *
   * @param touchXs x-coordinates of touches
   * @param touchYs y-coordinates of touches
   * @param touchCount number of touches
   * @param k maximum candidates to return per length bucket
   * @return list of candidate words, spatially nearest first
   */
  @NonNull
  public List<Min2mVocabulary.CandidateWord> query(
      float[] touchXs, float[] touchYs, int touchCount, int k) {
    return query(touchXs, touchYs, touchCount, k, -1, 2);
  }

  /**
   * Queries the spatial index with configurable length range.
   *
   * @param touchXs x-coordinates of touches
   * @param touchYs y-coordinates of touches
   * @param touchCount number of touches
   * @param k maximum candidates to return per length bucket
   * @param minLenOffset minimum length offset from touchCount (e.g., -1 for touchCount-1)
   * @param maxLenOffset maximum length offset from touchCount (e.g., 2 for touchCount+2)
   * @return list of candidate words, spatially nearest first
   */
  @NonNull
  public List<Min2mVocabulary.CandidateWord> query(
      float[] touchXs, float[] touchYs, int touchCount, int k,
      int minLenOffset, int maxLenOffset) {
    // Snapshot volatile fields for a consistent view throughout the query
    final KdNode[] trees = mTrees;
    final IndexEntry[][] entries = mEntries;
    final boolean is1D = mIs1D;
    if (!mIsBuilt || trees == null || entries == null || touchCount < 1) {
      return Collections.emptyList();
    }

    List<Min2mVocabulary.CandidateWord> results = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();

    // Query length buckets in the specified range
    int minLen = Math.max(1, touchCount + minLenOffset);
    int maxLen = touchCount + maxLenOffset;
    for (int len = minLen; len <= maxLen; len++) {
      if (len > MAX_INDEXED_LENGTH) continue;
      if (trees[len] == null || entries[len] == null) continue;

      // Build the query vector from touch positions, projected to this word length
      float[] queryVec = buildQueryVector(touchXs, touchYs, touchCount, len, is1D);
      if (queryVec == null) continue;

      // Verify dimensions match the tree entries
      int expectedDims = is1D ? len : len * 2;
      if (queryVec.length != expectedDims) continue;

      List<KnnCandidate> heap = new ArrayList<>(k + 1);
      int queryK = (len == touchCount) ? k : k / 2;
      // Dynamic visit limit: full search for low-dim trees, smoothly
      // reduced for higher dims where branch-and-bound degrades.
      mVisitLimit = computeVisitLimit(entries[len].length, expectedDims);
      mNodesVisited = 0;
      knnSearch(trees[len], entries[len], queryVec, queryK, heap, expectedDims);

      Collections.sort(heap, (a, b) -> Float.compare(a.distSq, b.distSq));
      for (KnnCandidate c : heap) {
        Min2mVocabulary.CandidateWord w = entries[len][c.entryIndex].word;
        if (seen.add(w.text)) {
          results.add(w);
        }
      }
    }

    return results;
  }

  // --- KD-tree construction ---

  /**
   * Recursively builds a kd-tree from the given entries.
   *
   * @param entries all entries for this word length
   * @param indices index array for the current subset
   * @param lo inclusive lower bound in indices
   * @param hi inclusive upper bound in indices
   * @param depth current recursion depth (determines split dimension)
   * @param dims total dimensions
   * @return root node of the subtree
   */
  @Nullable
  private KdNode buildTree(IndexEntry[] entries, int[] indices, int lo, int hi, int depth, int dims) {
    if (lo > hi) return null;

    int splitDim = depth % dims;

    // Find median by sorting the subset on splitDim
    // Using insertion sort for small subsets, partial sort for larger
    int mid = lo + (hi - lo) / 2;
    nthElement(entries, indices, lo, hi, mid, splitDim);

    KdNode node = new KdNode();
    node.entryIndex = indices[mid];
    node.splitDim = splitDim;
    node.splitValue = entries[indices[mid]].position[splitDim];

    node.left = buildTree(entries, indices, lo, mid - 1, depth + 1, dims);
    node.right = buildTree(entries, indices, mid + 1, hi, depth + 1, dims);

    return node;
  }

  /**
   * Partially sorts indices so that the element at position k is the k-th smallest by the given
   * dimension. Elements before k are ≤ and elements after are ≥ (quickselect algorithm).
   */
  private void nthElement(IndexEntry[] entries, int[] indices, int lo, int hi, int k, int dim) {
    while (lo < hi) {
      // Partition around pivot
      int pivotIdx = lo + (hi - lo) / 2;
      float pivotVal = entries[indices[pivotIdx]].position[dim];

      // Move pivot to end
      swap(indices, pivotIdx, hi);
      int storeIdx = lo;
      for (int i = lo; i < hi; i++) {
        if (entries[indices[i]].position[dim] < pivotVal) {
          swap(indices, i, storeIdx);
          storeIdx++;
        }
      }
      swap(indices, storeIdx, hi);

      if (storeIdx == k) return;
      else if (k < storeIdx) hi = storeIdx - 1;
      else lo = storeIdx + 1;
    }
  }

  private static void swap(int[] arr, int i, int j) {
    int tmp = arr[i];
    arr[i] = arr[j];
    arr[j] = tmp;
  }

  // --- KD-tree k-nearest-neighbor search ---

  /** Per-query visit counter and limit. Reset before each knnSearch call. */
  private int mNodesVisited;
  private int mVisitLimit;

  /**
   * Computes a dynamic visit limit based on tree size and dimensionality.
   * Scales smoothly: low-dim trees get near-full coverage (kd-tree prunes well),
   * high-dim trees get proportionally less (diminishing pruning returns).
   * Small trees always get full coverage regardless of dimensions.
   *
   * <p>Formula: visits = treeSize × (6 / dims), floored at treeSize for dims ≤ 6,
   * with a minimum of 5000 to ensure small trees are fully explored.
   *
   * @param treeSize number of entries in the tree
   * @param dims dimensionality of the position vectors
   * @return maximum nodes to visit
   */
  private static int computeVisitLimit(int treeSize, int dims) {
    if (dims <= 6) return treeSize; // full search — 4-6D prunes well
    // Smooth scaling: visit proportion shrinks as dims increase
    // 7D → 86%, 8D → 75%, 10D → 60%, 12D → 50%, 14D → 43%
    int limit = treeSize * 6 / dims;
    return Math.max(limit, Math.min(treeSize, 5000));
  }

  /**
   * Searches the kd-tree for the k nearest neighbors to the query point. Uses the standard
   * branch-and-bound algorithm with a bounded max-heap of size k, plus a dynamic visit
   * limit for approximate nearest neighbor in high dimensions.
   */
  private void knnSearch(
      KdNode node, IndexEntry[] entries, float[] query, int k, List<KnnCandidate> heap, int dims) {
    if (node == null || mNodesVisited >= mVisitLimit) return;
    mNodesVisited++;

    // Compute squared distance from query to this node's point
    float[] nodePos = entries[node.entryIndex].position;
    float distSq = squaredDistance(query, nodePos, dims);

    // Insert into bounded max-heap (largest distance at index 0 for pruning)
    if (heap.size() < k) {
      heap.add(new KnnCandidate(node.entryIndex, distSq));
      if (heap.size() == k) {
        // Find max and move to front for O(1) pruning checks
        swapMaxToFront(heap);
      }
    } else if (distSq < heap.get(0).distSq) {
      // Replace the farthest neighbor, then restore max-at-front invariant
      heap.set(0, new KnnCandidate(node.entryIndex, distSq));
      swapMaxToFront(heap);
    }

    // Determine which side of the split to search first
    float diff = query[node.splitDim] - node.splitValue;
    KdNode nearChild = diff <= 0 ? node.left : node.right;
    KdNode farChild = diff <= 0 ? node.right : node.left;

    // Always search the near side
    knnSearch(nearChild, entries, query, k, heap, dims);

    // Only search the far side if the splitting plane is closer than the current k-th best
    float splitDistSq = diff * diff;
    if (heap.size() < k || splitDistSq < heap.get(0).distSq) {
      knnSearch(farChild, entries, query, k, heap, dims);
    }
  }

  /** Squared Euclidean distance between two vectors. */
  private static float squaredDistance(float[] a, float[] b, int dims) {
    float sum = 0f;
    for (int i = 0; i < dims; i++) {
      float d = a[i] - b[i];
      sum += d * d;
    }
    return sum;
  }

  /**
   * Finds the element with the largest distSq and swaps it to index 0.
   * O(k) per call vs O(k log k) for Collections.sort — ~7× faster for k=100.
   * Only the max element needs to be at a known position (for pruning checks);
   * the rest of the list can be in any order.
   */
  private static void swapMaxToFront(List<KnnCandidate> heap) {
    int maxIdx = 0;
    float maxDist = heap.get(0).distSq;
    for (int i = 1; i < heap.size(); i++) {
      if (heap.get(i).distSq > maxDist) {
        maxDist = heap.get(i).distSq;
        maxIdx = i;
      }
    }
    if (maxIdx != 0) {
      KnnCandidate tmp = heap.get(0);
      heap.set(0, heap.get(maxIdx));
      heap.set(maxIdx, tmp);
    }
  }

  // --- Position vector computation ---

  /**
   * Computes the position vector for a word. For a 1D keyboard, the vector has one dimension per
   * letter (the x-coordinate of each letter's key center). For a 2D keyboard, two dimensions per
   * letter (x, y). Apostrophes and non-letter characters are skipped (they don't have key
   * positions, and users don't type them on compact keyboards).
   *
   * <p>This matches Minuum's approach: words are points in "key-position space" where spatial
   * proximity corresponds to similar touch patterns.
   *
   * @param word the word text
   * @param letterLen pre-computed letter-only length
   * @param keyCenters key center positions
   * @param is1D whether keyboard is 1D
   * @return position vector, or null if any letter has no key position
   */
  @Nullable
  private static float[] computePositionVector(
      @NonNull String word, int letterLen,
      @NonNull Map<Integer, float[]> keyCenters, boolean is1D) {
    int dims = is1D ? letterLen : letterLen * 2;
    float[] vec = new float[dims];

    int dimIdx = 0;
    for (int i = 0; i < word.length(); ) {
      int cp = Character.codePointAt(word, i);
      i += Character.charCount(cp);

      // Skip non-letter characters (apostrophes, hyphens)
      if (!Character.isLetter(cp)) continue;

      int lowerCp = Character.toLowerCase(cp);
      float[] center = keyCenters.get(lowerCp);
      if (center == null) return null; // character not on keyboard

      if (is1D) {
        vec[dimIdx++] = center[0]; // x only
      } else {
        vec[dimIdx++] = center[0]; // x
        vec[dimIdx++] = center[1]; // y
      }
    }

    return vec;
  }

  /**
   * Builds a query vector from touch positions, projecting to the target word length. When
   * touchCount equals the target length, uses positions directly. When they differ (edit distance),
   * interpolates or subsamples touch positions to match the target dimensions.
   *
   * @param touchXs touch x-coordinates
   * @param touchYs touch y-coordinates
   * @param touchCount number of touches
   * @param targetLetterLen target word length (in letters)
   * @param is1D whether keyboard is 1D
   * @return query vector, or null if impossible
   */
  @Nullable
  private static float[] buildQueryVector(
      float[] touchXs, float[] touchYs, int touchCount, int targetLetterLen, boolean is1D) {
    if (targetLetterLen < 1) return null;

    int dims = is1D ? targetLetterLen : targetLetterLen * 2;
    float[] vec = new float[dims];

    if (touchCount == targetLetterLen) {
      // Exact match: direct mapping
      for (int i = 0; i < targetLetterLen; i++) {
        if (is1D) {
          vec[i] = touchXs[i];
        } else {
          vec[i * 2] = touchXs[i];
          vec[i * 2 + 1] = touchYs[i];
        }
      }
    } else {
      // Length mismatch: linearly interpolate touch positions to target length.
      // This handles both insertions (touchCount > targetLen: condense) and
      // deletions (touchCount < targetLen: stretch).
      for (int i = 0; i < targetLetterLen; i++) {
        // Map target position i to a fractional touch position
        float srcPos = (float) i * (touchCount - 1) / Math.max(1, targetLetterLen - 1);
        int srcIdx = Math.min((int) srcPos, touchCount - 1);
        float frac = srcPos - srcIdx;

        float x, y;
        if (srcIdx + 1 < touchCount && frac > 0) {
          // Interpolate between two adjacent touches
          x = touchXs[srcIdx] * (1 - frac) + touchXs[srcIdx + 1] * frac;
          y = touchYs[srcIdx] * (1 - frac) + touchYs[srcIdx + 1] * frac;
        } else {
          x = touchXs[Math.min(srcIdx, touchCount - 1)];
          y = touchYs[Math.min(srcIdx, touchCount - 1)];
        }

        if (is1D) {
          vec[i] = x;
        } else {
          vec[i * 2] = x;
          vec[i * 2 + 1] = y;
        }
      }
    }

    return vec;
  }

  /**
   * Counts the number of letter characters in a word, excluding apostrophes, hyphens, and other
   * non-letter characters. This is the "indexable length" used for kd-tree bucket assignment.
   */
  static int letterLength(@NonNull String word) {
    int count = 0;
    for (int i = 0; i < word.length(); ) {
      int cp = Character.codePointAt(word, i);
      if (Character.isLetter(cp)) count++;
      i += Character.charCount(cp);
    }
    return count;
  }

  /** Clears all trees and entries. */
  public void clear() {
    mTrees = null;
    mEntries = null;
    mKeyCenters = null;
    mIsBuilt = false;
  }
}
