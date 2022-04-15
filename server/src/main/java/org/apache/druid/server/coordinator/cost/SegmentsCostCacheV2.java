/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.coordinator.cost;

import com.google.common.base.Preconditions;
import com.google.common.collect.Ordering;
import org.apache.commons.math3.util.FastMath;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.Treap;
import org.apache.druid.java.util.common.granularity.DurationGranularity;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.server.coordinator.CostBalancerStrategy;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.joda.time.Interval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ListIterator;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SegmentsCostCache provides faster way to calculate cost function proposed in {@link CostBalancerStrategy}.
 * See https://github.com/apache/druid/pull/2972 for more details about the cost function.
 *
 * Joint cost for two segments (you can make formulas below readable by copy-pasting to
 * https://www.codecogs.com/latex/eqneditor.php):
 *
 *        cost(Y, Y) = \int_{x_0}^{x_1} \int_{y_0}^{y_1} e^{-\lambda |x-y|}dxdy
 * or
 *        cost(Y, Y) = e^{y_0 + y_1} (e^{x_0} - e^{x_1})(e^{y_0} - e^{y_1})  (*)
 *                                                                          if x_0 <= x_1 <= y_0 <= y_1
 * (*) lambda coefficient is omitted for simplicity.
 *
 * For a group of segments {S_xi}, i = {0, n} total joint cost with segment S_y could be calculated as:
 *
 *        cost(Y, Y) = \sum cost(X_i, Y) =  e^{y_0 + y_1} (e^{y_0} - e^{y_1}) \sum (e^{xi_0} - e^{xi_1})
 *                                                                          if xi_0 <= xi_1 <= y_0 <= y_1
 * and
 *        cost(Y, Y) = \sum cost(X_i, Y) = (e^{y_0} - e^{y_1}) \sum e^{xi_0 + xi_1} (e^{xi_0} - e^{xi_1})
 *                                                                          if y_0 <= y_1 <= xi_0 <= xi_1
 *
 * SegmentsCostCache stores pre-computed sums for a group of segments {S_xi}:
 *
 *      1) \sum (e^{xi_0} - e^{xi_1})                      ->  leftSum
 *      2) \sum e^{xi_0 + xi_1} (e^{xi_0} - e^{xi_1})      ->  rightSum
 *
 * so that calculation of joint cost function for segment S_y became a O(1 + m) complexity task, where m
 * is the number of segments in {S_xi} that overlaps S_y.
 *
 * Segments are stored in buckets. Bucket is a subset of segments contained in SegmentsCostCache, so that
 * startTime of all segments inside a bucket are in the same time interval (with some granularity):
 *
 *  |------------------------|--------------------------|-----------------------|--------  ....
 *  t_0                    t_0+D                     t_0 + 2D                t0 + 3D       ....
 *      S_x1  S_x2  S_x3          S_x4  S_x5  S_x6          S_x7  S_x8  S_x9
 *         bucket1                  bucket2                    bucket3
 *
 * Reasons to store segments in Buckets:
 *
 *     1) Cost function tends to 0 as distance between segments' intervals increases; buckets
 *        are used to avoid redundant 0 calculations for thousands of times
 *     2) To reduce number of calculations when segment is added or removed from SegmentsCostCache
 *     3) To avoid infinite values during exponents calculations
 *
 */
public class SegmentsCostCacheV2
{
  /**
   * HALF_LIFE_DAYS defines how fast joint cost function tends to 0 as distance between segments' intervals increasing.
   * The value of 1 day means that cost function of co-locating two segments which have 1 days between their intervals
   * is 0.5 of the cost, if the intervals are adjacent. If the distance is 2 days, then 0.25, etc.
   */
  private static final double HALF_LIFE_DAYS = 1.0;
  private static final double LAMBDA = Math.log(2) / HALF_LIFE_DAYS;
  private static final double MILLIS_FACTOR = TimeUnit.DAYS.toMillis(1) / LAMBDA;

  /**
   * LIFE_THRESHOLD is used to avoid calculations for segments that are "far"
   * from each other and thus cost(Y,Y) ~ 0 for these segments
   */
  private static final long LIFE_THRESHOLD = TimeUnit.DAYS.toMillis(30);

  /**
   * Bucket interval defines duration granularity for segment buckets. Number of buckets control the trade-off
   * between updates (add/remove segment operation) and joint cost calculation:
   *        1) updates complexity is increasing when number of buckets is decreasing (as buckets contain more segments)
   *        2) joint cost calculation complexity is increasing with increasing of buckets number
   */
  private static final long BUCKET_INTERVAL = TimeUnit.DAYS.toMillis(15);
  private static final DurationGranularity BUCKET_GRANULARITY = new DurationGranularity(BUCKET_INTERVAL, 0);

  private static final Comparator<Interval> INTERVAL_COMPARATOR = Comparators.intervalsByStartThenEnd();

  private static final Comparator<Bucket> BUCKET_INTERVAL_COMPARATOR =
      Comparator.comparing(Bucket::getInterval, Comparators.intervalsByStartThenEnd());

  private static final Ordering<Interval> INTERVAL_ORDERING = Ordering.from(Comparators.intervalsByStartThenEnd());
  private static final Ordering<Bucket> BUCKET_ORDERING = Ordering.from(BUCKET_INTERVAL_COMPARATOR);

  private final ArrayList<Bucket> sortedBuckets;
  private final ArrayList<Interval> intervals;

  SegmentsCostCacheV2(ArrayList<Bucket> sortedBuckets)
  {
    this.sortedBuckets = Preconditions.checkNotNull(sortedBuckets, "buckets should not be null");
    this.intervals = sortedBuckets.stream().map(Bucket::getInterval).collect(Collectors.toCollection(ArrayList::new));
    Preconditions.checkArgument(
        BUCKET_ORDERING.isOrdered(sortedBuckets),
        "buckets must be ordered by interval"
    );
  }

  public double cost(DataSegment segment)
  {
    double cost = 0.0;
    int index = Collections.binarySearch(intervals, segment.getInterval(), Comparators.intervalsByStartThenEnd());
    index = (index >= 0) ? index : -index - 1;

    for (ListIterator<Bucket> it = sortedBuckets.listIterator(index); it.hasNext(); ) {
      Bucket bucket = it.next();
      if (!bucket.inCalculationInterval(segment)) {
        break;
      }
      cost += bucket.cost(segment);
    }

    for (ListIterator<Bucket> it = sortedBuckets.listIterator(index); it.hasPrevious(); ) {
      Bucket bucket = it.previous();
      if (!bucket.inCalculationInterval(segment)) {
        break;
      }
      cost += bucket.cost(segment);
    }

    return cost;
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static class Builder
  {
    private final NavigableMap<Interval, Bucket.Builder> buckets = new TreeMap<>(Comparators.intervalsByStartThenEnd());

    public Builder addSegment(DataSegment segment)
    {
      Bucket.Builder builder = buckets.computeIfAbsent(getBucketInterval(segment), Bucket::builder);
      builder.addSegment(segment);
      return this;
    }

    public Builder removeSegment(DataSegment segment)
    {
      Interval interval = getBucketInterval(segment);
      buckets.computeIfPresent(
          interval,
          // If there are no move segments, returning null in computeIfPresent() removes the interval from the buckets
          // map
          (i, builder) -> builder.removeSegment(segment).isEmpty() ? null : builder
      );
      return this;
    }

    public boolean isEmpty()
    {
      return buckets.isEmpty();
    }

    public SegmentsCostCacheV2 build()
    {
      return new SegmentsCostCacheV2(
          buckets
              .values()
              .stream()
              .map(Bucket.Builder::build)
              .collect(Collectors.toCollection(ArrayList::new))
      );
    }

    private static Interval getBucketInterval(DataSegment segment)
    {
      return BUCKET_GRANULARITY.bucket(segment.getInterval().getStart());
    }
  }

  static class Bucket
  {
    private final Interval interval;
    private final Interval calculationInterval;
    private final ArrayList<Interval> sortedIntervals;
    private final double[] leftSum;
    private final double[] rightSum;

    private final double[] cumStart;
    private final double[] cumStartExp;
    private final double[] cumStartExpInv;
    private final double[] cumEnd;
    private final double[] cumEndExp;
    private final double[] cumEndExpInv;

    Bucket(Interval interval, ArrayList<Interval> sortedIntervals, double[] leftSum, double[] rightSum)
    {
      this.interval = Preconditions.checkNotNull(interval, "interval");
      this.sortedIntervals = Preconditions.checkNotNull(sortedIntervals, "sortedSegments");
      this.leftSum = Preconditions.checkNotNull(leftSum, "leftSum");
      this.rightSum = Preconditions.checkNotNull(rightSum, "rightSum");
      Preconditions.checkArgument(sortedIntervals.size() == leftSum.length && sortedIntervals.size() == rightSum.length);
      Preconditions.checkArgument(INTERVAL_ORDERING.isOrdered(sortedIntervals));
      this.calculationInterval = new Interval(
          interval.getStart().minus(LIFE_THRESHOLD),
          interval.getEnd().plus(LIFE_THRESHOLD)
      );

      int n = leftSum.length;

      cumStart = new double[n + 1];
      cumStartExp = new double[n + 1];
      cumStartExpInv = new double[n + 1];
      cumEnd = new double[n + 1];
      cumEndExp = new double[n + 1];
      cumEndExpInv = new double[n + 1];
      for (int i = 0; i < n; i++) {
        double start = convertStart(sortedIntervals.get(i), interval);
        cumStart[i + 1] = cumStart[i] + start;
        cumStartExp[i + 1] = cumStartExp[i] + FastMath.exp(start);
        cumStartExpInv[i + 1] = cumStartExpInv[i] + FastMath.exp(-start);

        double end = convertEnd(sortedIntervals.get(i), interval);
        cumEnd[i + 1] = cumEnd[i] + end;
        cumEndExp[i + 1] = cumEndExp[i] + FastMath.exp(end);
        cumEndExpInv[i + 1] = cumEndExpInv[i] + FastMath.exp(-end);
      }
    }

    Interval getInterval()
    {
      return interval;
    }

    boolean inCalculationInterval(DataSegment dataSegment)
    {
      return calculationInterval.overlaps(dataSegment.getInterval());
    }

    double cost(DataSegment dataSegment)
    {
      // cost is calculated relatively to bucket start (which is considered as 0)
      double t0 = convertStart(dataSegment.getInterval(), interval);
      double t1 = convertEnd(dataSegment.getInterval(), interval);

      // avoid calculation for segments outside of LIFE_THRESHOLD
      if (!inCalculationInterval(dataSegment)) {
        throw new ISE("Segment is not within calculation interval");
      }

      int index = Collections.binarySearch(sortedIntervals, dataSegment.getInterval(), INTERVAL_COMPARATOR);
      index = (index >= 0) ? index : -index - 1;
      return leftCost(dataSegment, t0, t1, index) + rightCost(dataSegment, t0, t1, index);
    }

    private double leftCost(DataSegment dataSegment, double t0, double t1, int index)
    {
      if (index - 1 < 0) {
        return 0;
      }
      double exp0 = FastMath.exp(t0);
      double expInv0 = 1 / exp0;
      double exp1 = FastMath.exp(t1);
      double expInv1 = 1 / exp1;
      double leftCost = 0.0;
      // add to cost all left-overlapping segments
      int rightBound = index - 1;
      int leftBound = leftBoundary(0, index - 1, dataSegment.getInterval());
      leftCost += 2 * (cumEnd[rightBound + 1] - cumEnd[leftBound]);
      leftCost -= 2 * (rightBound - leftBound + 1) * t0;
      leftCost += expInv1 * (cumStartExp[rightBound + 1] - cumStartExp[leftBound]);
      leftCost += exp0 * (cumEndExpInv[rightBound + 1] - cumEndExpInv[leftBound]);
      leftCost -= expInv0 * (cumStartExp[rightBound + 1] - cumStartExp[leftBound]);
      leftCost -= expInv1 * (cumEndExp[rightBound + 1] - cumEndExp[leftBound]);
      // add left-non-overlapping segments
      if (leftBound > 0) {
        leftCost += leftSum[leftBound - 1] * (expInv1 - expInv0);
      }
      return leftCost;
    }

    private double rightCost(DataSegment dataSegment, double t0, double t1, int index)
    {
      int n = leftSum.length;
      if (index >= n) {
        return 0;
      }
      double exp0 = FastMath.exp(t0);
      double exp1 = FastMath.exp(t1);
      double expInv1 = 1 / exp1;
      double rightCost = 0.0;
      int leftBound = index;
      int rightBound = rightBoundary(index, n - 1, dataSegment.getInterval());
      // add all right-overlapping segments
      rightCost += 2 * (rightBound - leftBound + 1) * t1;
      rightCost -= 2 * (cumStart[rightBound + 1] - cumStart[leftBound]);
      rightCost += exp0 * (cumEndExpInv[rightBound + 1] - cumEndExpInv[leftBound]);
      rightCost += expInv1 * (cumStartExp[rightBound + 1] - cumStartExp[leftBound]);
      rightCost -= exp0 * (cumStartExpInv[rightBound + 1] - cumStartExpInv[leftBound]);
      rightCost -= exp1 * (cumEndExpInv[rightBound + 1] - cumEndExpInv[leftBound]);
      // add right-non-overlapping segments
      if (rightBound + 1 < n) {
        rightCost += rightSum[rightBound + 1] * (exp0 - exp1);
      }
      return rightCost;
    }

    private int leftBoundary(int l, int r, Interval interval)
    {
      if (l == r) {
        return interval.overlaps(sortedIntervals.get(l)) ? l : r + 1;
      }
      int m = (l + r) / 2;
      if (interval.overlaps(sortedIntervals.get(m))) {
        return leftBoundary(l, m, interval);
      } else {
        return leftBoundary(m + 1, r, interval);
      }
    }

    private int rightBoundary(int l, int r, Interval interval)
    {
      if (l == r) {
        return interval.overlaps(sortedIntervals.get(r)) ? r : l - 1;
      }
      int m = (l + r + 1) / 2;
      if (interval.overlaps(sortedIntervals.get(m))) {
        return rightBoundary(m, r, interval);
      } else {
        return rightBoundary(l, m - 1, interval);
      }
    }

    private static double convertStart(Interval interval, Interval reference)
    {
      return toLocalInterval(interval.getStartMillis(), reference);
    }

    private static double convertEnd(Interval interval, Interval reference)
    {
      return toLocalInterval(interval.getEndMillis(), reference);
    }

    private static double toLocalInterval(long millis, Interval interval)
    {
      return millis / MILLIS_FACTOR - interval.getStartMillis() / MILLIS_FACTOR;
    }

    public static Builder builder(Interval interval)
    {
      return new Builder(interval);
    }

    static class Builder
    {
      protected final Interval interval;
      private SegmentTreap treap = new SegmentTreap();
      public Builder(Interval interval)
      {
        this.interval = interval;
      }

      public Builder addSegment(DataSegment dataSegment)
      {
        if (!interval.contains(dataSegment.getInterval().getStartMillis())) {
          throw new ISE("Failed to add segment to bucket: interval is not covered by this bucket");
        }

        // all values are pre-computed relatively to bucket start (which is considered as 0)
        double t0 = convertStart(dataSegment.getInterval(), interval);
        double t1 = convertEnd(dataSegment.getInterval(), interval);

        double leftValue = FastMath.exp(t0) - FastMath.exp(t1);
        double rightValue = FastMath.exp(-t1) - FastMath.exp(-t0);

        SegmentAndSum segmentAndSum = new SegmentAndSum(dataSegment, leftValue, rightValue);

        // left/right value should be added to left/right sums for elements greater/lower than current segment
        treap.update(segmentAndSum, Pair.of(leftValue, 0.0), true);
        treap.update(segmentAndSum, Pair.of(0.0, rightValue), false);

        // leftSum_i = leftValue_i + \sum leftValue_j = leftValue_i + leftSum_{i-1} , j < i
        SegmentAndSum lower = treap.lower(segmentAndSum);
        if (lower != null) {
          segmentAndSum.leftSum = leftValue + lower.leftSum;
        }

        // rightSum_i = rightValue_i + \sum rightValue_j = rightValue_i + rightSum_{i+1} , j > i
        SegmentAndSum higher = treap.upper(segmentAndSum);
        if (higher != null) {
          segmentAndSum.rightSum = rightValue + higher.rightSum;
        }

        if (!treap.insert(segmentAndSum)) {
          throw new ISE("expect new segment");
        }
        return this;
      }

      public Builder removeSegment(DataSegment dataSegment)
      {
        SegmentAndSum segmentAndSum = new SegmentAndSum(dataSegment, 0.0, 0.0);

        if (!treap.remove(segmentAndSum)) {
          return this;
        }

        double t0 = convertStart(dataSegment.getInterval(), interval);
        double t1 = convertEnd(dataSegment.getInterval(), interval);

        double leftValue = FastMath.exp(t0) - FastMath.exp(t1);
        double rightValue = FastMath.exp(-t1) - FastMath.exp(-t0);

        treap.update(segmentAndSum, Pair.of(-leftValue, 0.0), true);
        treap.update(segmentAndSum, Pair.of(0.0, -rightValue), false);

        return this;
      }

      public boolean isEmpty()
      {
        return treap.isEmpty();
      }

      public Bucket build()
      {
        ArrayList<Interval> intervalsList = new ArrayList<>();
        double[] leftSum = new double[treap.size()];
        double[] rightSum = new double[treap.size()];
        int i = 0;
        for (SegmentAndSum segmentAndSum : treap.toList()) {
          intervalsList.add(segmentAndSum.interval);
          leftSum[i] = segmentAndSum.leftSum;
          rightSum[i] = segmentAndSum.rightSum;
          ++i;
        }
        treap = null;
        long bucketEndMillis = intervalsList
            .stream()
            .mapToLong(Interval::getEndMillis)
            .max()
            .orElseGet(interval::getEndMillis);
        return new Bucket(Intervals.utc(interval.getStartMillis(), bucketEndMillis), intervalsList, leftSum, rightSum);
      }
    }
  }

  static class SegmentAndSum implements Comparable<SegmentAndSum>
  {
    private final Interval interval;
    private final SegmentId segmentId;
    private double leftSum;
    private double rightSum;

    SegmentAndSum(DataSegment dataSegment, double leftSum, double rightSum)
    {
      this.interval = dataSegment.getInterval();
      this.segmentId = dataSegment.getId();
      this.leftSum = leftSum;
      this.rightSum = rightSum;
    }

    @Override
    public int compareTo(SegmentAndSum o)
    {
      int c = Comparators.intervalsByStartThenEnd().compare(interval, o.interval);
      return c != 0 ? c : segmentId.compareTo(o.segmentId);
    }

    @Override
    public boolean equals(Object obj)
    {
      throw new UnsupportedOperationException("Use SegmentAndSum.compareTo()");
    }

    @Override
    public int hashCode()
    {
      throw new UnsupportedOperationException();
    }
  }

  public static class SegmentTreap extends Treap<SegmentAndSum, Pair<Double, Double>>
  {

    static final Pair<Double, Double> ZERO = Pair.of(0.0, 0.0);

    @Override
    protected Pair<Double, Double> getVal(SegmentAndSum val)
    {
      return Pair.of(val.leftSum, val.rightSum);
    }

    @Override
    protected SegmentAndSum setVal(SegmentAndSum val, Pair<Double, Double> lazy)
    {
      val.leftSum += lazy.lhs;
      val.rightSum += lazy.rhs;
      return val;
    }

    @Override
    protected Pair<Double, Double> zero()
    {
      return ZERO;
    }

    @Override
    protected Pair<Double, Double> add(Pair<Double, Double> a, Pair<Double, Double> b)
    {
      return Pair.of(a.lhs + b.lhs, a.rhs + b.rhs);
    }

    @Override
    protected Pair<Double, Double> multiply(int a, Pair<Double, Double> b)
    {
      return Pair.of(a * b.lhs, a * b.rhs);
    }
  }
}
