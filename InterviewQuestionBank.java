import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InterviewQuestionBank {
    private InterviewQuestionBank() {}

    static List<Map<String, Object>> levelFourQuestions() {
        List<Map<String, Object>> questions = new ArrayList<>();
        questions.add(question("Amazon", "Trapping Rain Water",
                "Given bar heights across a skyline, calculate how many unit blocks of water remain after rain.",
                List.of("Use two pointers and track the greatest wall seen from each side.",
                        "Sort the heights and sum every gap between adjacent bars.",
                        "For every pair of bars, rescan the full range between them.",
                        "Use a queue and breadth-first search from the tallest bar."),
                0, """
                        Approach: Two pointers, with O(1) extra space.
                        1. Start left and right at the ends; keep leftMax and rightMax.
                        2. Move the side with the smaller current height inward.
                        3. Update that side's maximum; the water at this bar is maximum minus height.
                        4. Add each contribution to the result.
                        Pseudocode:
                          while left < right:
                            if height[left] <= height[right]:
                              leftMax = max(leftMax, height[left])
                              water += leftMax - height[left]
                              left += 1
                            else:
                              rightMax = max(rightMax, height[right])
                              water += rightMax - height[right]
                              right -= 1
                        Complexity: O(n) time, O(1) extra space."""));
        questions.add(question("Swiggy", "Binary Strings Without Adjacent Ones",
                "For a requested length n, count the binary strings that never place two 1s next to each other.",
                List.of("Use a recurrence: counts[n] = counts[n - 1] + counts[n - 2].",
                        "There are exactly 2n strings for every n.",
                        "Sort all strings lexicographically and count transitions.",
                        "Use a graph traversal over all 2^n strings."),
                0, """
                        Approach: Dynamic programming by the final bit.
                        1. Let dp[n] count valid strings of length n.
                        2. A valid string ending in 0 can follow any valid length n-1 string.
                        3. A string ending in 1 must follow a length n-2 string with 10 appended.
                        4. Therefore dp[n] = dp[n-1] + dp[n-2].
                        Pseudocode:
                          if n == 0: return 1
                          previousTwo = 1
                          previousOne = 2
                          repeat length from 2 through n:
                            current = previousOne + previousTwo
                            previousTwo = previousOne
                            previousOne = current
                          return previousOne
                        Complexity: O(n) time, O(1) extra space.
                        For very large n, use fast-doubling Fibonacci to reduce time to O(log n)."""));
        questions.add(question("Blinkit", "Longest Substring Without Repeating Characters",
                "Find the length of the longest contiguous part of a string in which every character appears once.",
                List.of("Maintain a sliding window and move its left edge past a repeated character.",
                        "Sort the string and count the longest run.",
                        "Compare every substring using a fresh nested scan.",
                        "Use a stack and remove characters whenever the window grows."),
                0, """
                        Approach: Sliding window with each character's last index.
                        1. Scan from left to right and store the most recent index for each character.
                        2. If a character repeats inside the current window, move the left boundary to one past its last index.
                        3. Update the best length at each position.
                        Pseudocode:
                          left = best = 0
                          last = empty map
                          for right from 0 through text.length - 1:
                            c = text[right]
                            if last contains c: left = max(left, last[c] + 1)
                            last[c] = right
                            best = max(best, right - left + 1)
                          return best
                        Complexity: O(n) expected time, O(k) space for k distinct characters."""));
        questions.add(question("Flipkart", "Minimum in a Rotated Sorted Array",
                "An ascending array with distinct values was rotated an unknown number of times. Find its minimum in logarithmic time.",
                List.of("Use binary search and compare the midpoint with the rightmost value.",
                        "Scan every item and retain the smallest value.",
                        "Sort the array again and return its first value.",
                        "Compare only the first and last values."),
                0, """
                        Approach: Binary search over the rotation boundary.
                        1. Keep low and high as the remaining search interval.
                        2. If middle is greater than high, the minimum lies to the right.
                        3. Otherwise, middle may be the minimum, so keep it and discard the right half.
                        4. When low equals high, that position is the minimum.
                        Pseudocode:
                          low = 0; high = length - 1
                          while low < high:
                            middle = low + (high - low) / 2
                            if values[middle] > values[high]: low = middle + 1
                            else: high = middle
                          return values[low]
                        Complexity: O(log n) time, O(1) extra space.
                        The distinct-value assumption is important; duplicates can require a linear worst case."""));
        questions.add(question("PhonePe", "Search in a Rotated Sorted Array",
                "Search for a target in a rotated ascending array with distinct values and return its index, or -1.",
                List.of("Binary-search the sorted half and discard the half that cannot contain the target.",
                        "Run ordinary binary search without checking the rotation.",
                        "Sort a copy and return the target's position in that copy.",
                        "Use two pointers and compare only the endpoints."),
                0, """
                        Approach: Modified binary search.
                        1. Compute the middle of the current interval.
                        2. If it matches the target, return it.
                        3. At least one half is sorted. Check whether the target falls within that half's bounds.
                        4. Keep that half when it can contain the target; otherwise search the other half.
                        Pseudocode:
                          while low <= high:
                            mid = low + (high - low) / 2
                            if values[mid] == target: return mid
                            if values[low] <= values[mid]:
                              if values[low] <= target < values[mid]: high = mid - 1
                              else: low = mid + 1
                            else:
                              if values[mid] < target <= values[high]: low = mid + 1
                              else: high = mid - 1
                          return -1
                        Complexity: O(log n) time, O(1) extra space."""));
        questions.add(question("Paytm", "Stable Linked-List Partition",
                "Reorder a linked list around pivot x: nodes below x come first, followed by the rest, preserving order within both groups.",
                List.of("Build two tail-linked lists and join them after traversal.",
                        "Sort all nodes by their values.",
                        "Reverse the list, then split it at the pivot.",
                        "Copy values into a set and rebuild in sorted order."),
                0, """
                        Approach: Two dummy-headed lists.
                        1. Create a before list and an after list, each with a tail pointer.
                        2. Walk the original nodes once; detach each node before appending it to the appropriate tail.
                        3. Connect the before tail to the after head.
                        4. Terminate the after tail with null and return the before head.
                        Complexity: O(n) time and O(1) extra node storage (the list nodes are reused).
                        Stability comes from appending nodes in their original traversal order."""));
        questions.add(question("JPMorgan Chase", "Largest Square of Ones",
                "In a binary grid, find the area of the largest square whose cells are all 1.",
                List.of("Use dynamic programming; each 1-cell extends the minimum of its top, left, and upper-left neighbors.",
                        "Count all 1s and take the square root.",
                        "Use a single row prefix sum and choose the widest row.",
                        "Find the largest connected component of 1s."),
                0, """
                        Approach: Dynamic programming.
                        1. Let dp[r][c] be the side length of the largest all-1 square ending at cell (r,c).
                        2. For a 1-cell away from the boundary, dp[r][c] = 1 + min(top, left, diagonal).
                        3. A 0-cell has dp value 0. Track the largest side found.
                        4. Return largestSide * largestSide.
                        Pseudocode:
                          for each cell (r, c):
                            if grid[r][c] == 1:
                              dp[r][c] = 1 + min(dp[r-1][c], dp[r][c-1], dp[r-1][c-1])
                              largestSide = max(largestSide, dp[r][c])
                          return largestSide * largestSide
                        Complexity: O(rows * columns) time and O(rows * columns) space.
                        A rolling row reduces auxiliary space to O(columns)."""));
        questions.add(question("NVIDIA", "Maximum Subarray Sum",
                "Choose a non-empty contiguous segment of an integer array whose sum is as large as possible.",
                List.of("Use Kadane's recurrence: extend the previous segment or start at the current value.",
                        "Sort the values and add all positive numbers.",
                        "Use a sliding window that only expands to the right.",
                        "Use binary search on the answer."),
                0, """
                        Approach: Kadane's algorithm.
                        1. The best segment ending at the current value is either that value alone or the previous ending sum plus it.
                        2. Update the best answer seen globally.
                        3. Initialize both values from the first array element so all-negative arrays work.
                        Pseudocode:
                          endingHere = best = values[0]
                          for i from 1 through length - 1:
                            endingHere = max(values[i], endingHere + values[i])
                            best = max(best, endingHere)
                          return best
                        Complexity: O(n) time and O(1) extra space."""));
        questions.add(question("Google", "Count Islands After Each Land Addition",
                "A water-filled grid receives land updates one at a time. Report the island count after every update; adjacent means up, down, left, or right.",
                List.of("Use disjoint-set union; add a component, then union it with each existing land neighbor.",
                        "Re-run flood fill over the entire grid after every update.",
                        "Keep a count of land cells; it is always the island count.",
                        "Sort the updates and merge equal coordinates."),
                0, """
                        Approach: Disjoint-set union (union-find).
                        1. Map each cell to a unique integer id; initially every cell is water.
                        2. For a new land cell, ignore duplicate additions. Otherwise mark it land and increment islandCount.
                        3. Inspect its four neighbors. For each land neighbor in a different DSU component, union the sets and decrement islandCount.
                        4. Append islandCount after each update.
                        Complexity: O(q * alpha(m*n)) time for q updates on an m by n grid, and O(m*n) storage.
                        Use path compression and union by size/rank."""));
        questions.add(question("Amazon", "Top K Frequent Elements",
                "Return the k values that occur most often in an integer collection without sorting every distinct value by frequency.",
                List.of("Count values, then use frequency buckets and scan buckets from high to low.",
                        "Keep a sorted list of all input values and return its first k.",
                        "Use a breadth-first traversal of the input array.",
                        "Select k random values and remove duplicates."),
                0, """
                        Approach: Frequency map and buckets.
                        1. Count each value in a hash map.
                        2. Create buckets indexed by frequency; put each distinct value in its frequency bucket.
                        3. Visit buckets from the largest frequency downward and collect values until k are selected.
                        4. If a deterministic tie order is required, sort values inside each bucket.
                        Complexity: O(n + u) expected time and O(n + u) space, where u is the number of distinct values."""));
        questions.add(question("Swiggy", "Rotate a Square Matrix Clockwise",
                "Rotate an n by n matrix in place by 90 degrees clockwise.",
                List.of("Transpose across the main diagonal, then reverse each row.",
                        "Reverse the matrix's row order only.",
                        "Transpose, then reverse each column.",
                        "Shift every cell one position right a single time."),
                0, """
                        Approach: Transpose, then reverse each row.
                        1. Swap matrix[r][c] with matrix[c][r] for c greater than r.
                        2. Reverse every row.
                        3. The transpose changes rows into columns; reversing each row makes the rotation clockwise.
                        Complexity: O(n^2) time and O(1) auxiliary space."""));
        questions.add(question("Blinkit", "Group Anagrams",
                "Group words so that words with the same letters and letter counts appear together.",
                List.of("Use a canonical key (sorted letters or letter counts) to group words in a hash map.",
                        "Compare each pair of words character by character in the original order.",
                        "Group words by their first character.",
                        "Insert the words into a prefix trie and group by length."),
                0, """
                        Approach: Canonical signature plus hash map.
                        1. For each word, build a key by sorting its characters (or by counting characters).
                        2. Append the word to the list stored for that key.
                        3. Return the map's groups.
                        Complexity with sorted keys: O(sum(L log L)) time and O(total input characters) space.
                        A fixed alphabet-count signature can reduce key-building time to O(sum(L))."""));
        questions.add(question("Flipkart", "Sliding Window Maximum",
                "For every contiguous window of width k, report its maximum value.",
                List.of("Maintain a deque of indices in decreasing value order; remove expired indices from the front.",
                        "Re-sort each window independently.",
                        "Use a min-heap and report its smallest element.",
                        "Compute a prefix sum and divide by k."),
                0, """
                        Approach: Monotonic deque.
                        1. Store indices, keeping their values in decreasing order.
                        2. Before adding index i, remove indices outside the current window from the front.
                        3. Remove smaller-or-equal values from the back, then append i.
                        4. Once the first window is complete, the front index is its maximum.
                        Complexity: O(n) time because each index enters and leaves once; O(k) space."""));
        questions.add(question("PhonePe", "Longest Increasing Subsequence",
                "Find the length of the longest strictly increasing subsequence; chosen elements need not be adjacent.",
                List.of("Maintain minimum tail values and binary-search the first tail greater than or equal to each value.",
                        "Sort the input and return its length.",
                        "Use a sliding window because the subsequence is contiguous.",
                        "Compare only neighboring pairs."),
                0, """
                        Approach: Patience-sorting tails array.
                        1. For each value, binary-search tails for the first position whose value is >= it.
                        2. Replace that tail with the current value, or append if no such position exists.
                        3. The number of tails equals the longest strictly increasing subsequence length.
                        Complexity: O(n log n) time and O(n) space.
                        To reconstruct an actual subsequence, additionally track predecessor indices."""));
        questions.add(question("Paytm", "Arrange Values to Form the Largest Number",
                "Reorder non-negative integers so their concatenation is lexicographically largest; return one combined string.",
                List.of("Order a before b when concatenating a+b is greater than b+a.",
                        "Sort the numbers by their numeric value in ascending order.",
                        "Sort by the number of digits, then append.",
                        "Put all odd numbers before even numbers."),
                0, """
                        Approach: Pairwise concatenation comparator.
                        1. Convert every value to text.
                        2. For strings a and b, place a first when (a+b) is lexicographically larger than (b+a).
                        3. Concatenate the sorted strings.
                        4. If the result begins with zero, return a single \"0\".
                        Complexity: O(n log n * L) time, where L is the compared string length; O(total digits) output space."""));
        questions.add(question("JPMorgan Chase", "Count Subarrays with Sum K",
                "Count all contiguous subarrays whose values add up to target k; values may be negative.",
                List.of("Use a running prefix sum and a map of earlier prefix-sum frequencies.",
                        "Use a shrinking sliding window; negative values do not affect it.",
                        "Sort the array and search for pairs summing to k.",
                        "Build a set of values and count matches."),
                0, """
                        Approach: Prefix-sum frequency map.
                        1. Let prefix be the sum through the current index.
                        2. Any earlier prefix equal to prefix-k forms a subarray ending here with sum k.
                        3. Add the frequency of prefix-k to the answer, then record this prefix.
                        4. Seed the map with prefix 0 occurring once to count subarrays starting at index 0.
                        Complexity: O(n) expected time and O(n) space."""));
        questions.add(question("NVIDIA", "Product of Array Except Self",
                "Build an output where each position contains the product of every input value except the value at that position, without division.",
                List.of("Write prefix products, then multiply by a running suffix product.",
                        "Multiply all values and divide by the current value.",
                        "Sort the values and shift them by one position.",
                        "For every position, rescan the full input twice."),
                0, """
                        Approach: Prefix and suffix products.
                        1. Sweep left to right, writing the product of values strictly before each position.
                        2. Sweep right to left with a running suffix product and multiply it into each output position.
                        3. Update the suffix after writing; no division is needed and zero values work naturally.
                        Complexity: O(n) time and O(1) auxiliary space if the output array is not counted."""));
        questions.add(question("Google", "Dynamic URL Path Router",
                "Design register(pattern, handler) and match(path) for routes containing literal segments and named placeholders such as /orders/{orderId}/items.",
                List.of("Index path segments in a trie with separate literal and parameter edges; define a precedence rule for matches.",
                        "Store routes in an unordered list and return the first substring match.",
                        "Use a single regular expression that treats every segment as a wildcard.",
                        "Sort handlers alphabetically and choose the first."),
                0, """
                        Approach: Segment trie.
                        1. Split patterns and incoming paths into normalized path segments.
                        2. Insert literal segments into literal-child edges and parameter segments into a parameter edge that stores the parameter name.
                        3. While matching, prefer an exact literal edge; if absent, follow the parameter edge and capture its segment.
                        4. Accept only a terminal route after consuming every path segment. Specify how conflicting patterns are rejected or prioritized.
                        Complexity: O(L) trie traversal for L path segments, excluding ambiguity/backtracking cases.
                        Production concerns: normalize slashes, handle URL decoding safely, and avoid allowing encoded separators to change segment boundaries."""));
        return List.copyOf(questions);
    }

    private static Map<String, Object> question(String company, String title, String prompt,
                                                  List<String> options, int answer, String solution) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("q", company + " interview practice — " + title + ": " + prompt);
        item.put("explain", "Correct approach: " + options.get(answer));
        item.put("solution", solution);
        item.put("company", company);
        item.put("company_problem", true);
        item.put("source_type", "dsa");
        return item;
    }
}
