package defi.util;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.testenvironment.TxExecution;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.RepetitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility for testing contracts with complex control flows. */
public final class ExecutionUtil {
  private ExecutionUtil() {}

  private static final Logger logger = LoggerFactory.getLogger(ExecutionUtil.class);

  /**
   * Executes the subtree TxExecution by the given TxExecution event in an arbitrary and
   * unpredictable order. This helps in finding invalid assumptions in the contract code about the
   * event execution order.
   *
   * @param repetitionInfo Contains seed information for the execution order.
   * @param initialTxExecution The initial TxExecution events.
   * @return result object that can be asserted on.
   */
  public static EventTreeExecutionResult executeTxExecutionInUnpredictableOrder(
      JunitContractTest testInstance,
      RepetitionInfo repetitionInfo,
      List<TxExecution> initialTxExecution) {
    // Determine prioritizer
    final EventPrioritizer eventPriotizer = determinePrioritizer(repetitionInfo);
    logger.info("Using prioritizer: {}", eventPriotizer.name());

    // Setup event tracking
    final ArrayList<TxExecutionEvent> queuedEvents = new ArrayList<>();
    final Map<Integer, TxExecutionEvent> executedEvents = new HashMap<>();
    final ArrayList<TxExecutionEvent> failedEvents = new ArrayList<>();

    int idCounter = 0;

    for (final TxExecution txExecution : initialTxExecution) {
      for (final TxExecution spawnedEvent : txExecution.getSpawnedEvents()) {
        queuedEvents.add(new TxExecutionEvent(spawnedEvent, idCounter++, null));
      }
    }

    while (!queuedEvents.isEmpty()) {
      // Execute event from queue
      final TxExecutionEvent txExecutionEvent = eventPriotizer.popEvent().apply(queuedEvents);
      final TxExecution txExecution = txExecutionEvent.event();
      testInstance.blockchain.executeEventAsync(txExecution);

      if (!txExecution.isSuccess()) {
        failedEvents.add(txExecutionEvent);
      }

      // Queue new events
      final List<TxExecution> spawnedEvents = txExecution.getSpawnedEvents();
      executedEvents.put(txExecutionEvent.id(), txExecutionEvent);
      if (!spawnedEvents.isEmpty()) {
        for (final TxExecution spawnedEvent : spawnedEvents) {
          queuedEvents.add(new TxExecutionEvent(spawnedEvent, idCounter++, txExecutionEvent.id()));
        }
      }
    }

    return new EventTreeExecutionResult(
        testInstance, Map.copyOf(executedEvents), List.copyOf(failedEvents));
  }

  private static String formatEventTraceException(
      final JunitContractTest testInstance,
      final Map<Integer, TxExecutionEvent> executedEvents,
      final TxExecutionEvent failingTx) {
    final StringBuilder str = new StringBuilder();

    // Write initial event error
    final String nameOfFailingContract =
        contractName(testInstance, failingTx.event().getEvent().getEvent().getInner().target());
    str.append("Event to %s failed: ".formatted(nameOfFailingContract))
        .append(failingTx.event().getFailureCause().getErrorMessage())
        .append("\nEvent trace:\n");

    // Write event trace
    TxExecutionEvent txExecutionEvent = failingTx;
    boolean firstEvent = true;
    while (txExecutionEvent != null) {
      final TxExecution txExecution = txExecutionEvent.event();
      final String contractName =
          contractName(testInstance, txExecution.getEvent().getEvent().getInner().target());
      final String spawnedByText = firstEvent ? "happened in:" : "spawned by:";
      firstEvent = false;

      str.append(
          " %15s event %3d to %s\n".formatted(spawnedByText, txExecutionEvent.id(), contractName));
      txExecutionEvent =
          txExecutionEvent.originator() == null
              ? null
              : executedEvents.get(txExecutionEvent.originator());
    }
    return str.toString();
  }

  /**
   * Utility to determine a useful name for the given {@link BlockchainAddress} for debugging.
   *
   * <p>The problem: Methods are often given {@link BlockchainAddress}, with no easy way to
   * determine what the specific address actually corresponds to. This method attempts to map
   * "backwards" by introspecting over the given {@link JunitContractTest} to find any stored {@link
   * BlockchainAddress}.
   *
   * @param testInstance Test instance to check fields.
   * @param target Address to determine name for.
   * @return contract name. Never null, but may return hex data if no useful names were found.
   */
  public static String contractName(JunitContractTest testInstance, BlockchainAddress target) {
    try {
      for (final Field field : testInstance.getClass().getFields()) {
        field.setAccessible(true);
        final Object fieldValue = field.get(testInstance);
        if (target.equals(fieldValue)) {
          return field.getName();
        } else if (fieldValue instanceof List<?> ls) {
          final int idx = ls.indexOf(target);
          if (idx != -1) {
            return "%s[%s]".formatted(field.getName(), idx);
          }
        }
      }
    } catch (IllegalAccessException e) {
      return target.writeAsString();
    }
    return target.writeAsString();
  }

  /**
   * Wrapped {@link TxExecution}, with internal ids to track the event's order and originator.
   * Mainly used for debugging.
   *
   * @param event Wrapped event.
   * @param id Id of the event in the current execution.
   * @param originator Id of originating event. Null allowed.
   */
  record TxExecutionEvent(TxExecution event, int id, Integer originator) {}

  /**
   * Chooses the prioritizer to use for the given repetition.
   *
   * @param repetitionInfo Information about the current repetition. Used to choose a prioritizer
   *     implementation.
   * @return Prioritizer to use for this current test run.
   */
  private static EventPrioritizer determinePrioritizer(RepetitionInfo repetitionInfo) {
    final int idx = repetitionInfo.getCurrentRepetition() - 1;
    if (idx < HARD_CODED_PRIORITIZES.size()) {
      // Choose one of the hard-coded prioritezers.
      return HARD_CODED_PRIORITIZES.get(idx);
    } else {
      // Random sample out
      return EventPrioritizer.atRandom(idx);
    }
  }

  /**
   * Prioritizer of events. Used to determine the order to execute events in.
   *
   * @param name Name of the prioritizer. Used for debugging.
   * @param popEvent Function to determine the next event to execute.
   */
  record EventPrioritizer(
      String name, Function<List<TxExecutionEvent>, TxExecutionEvent> popEvent) {

    /**
     * Create new {@link EventPrioritizer} by ordering on a specific key. Defaults to outputting the
     * lowest value first.
     *
     * @param name Name of the prioritizer. Used for debugging.
     * @param keyExtractor Function to determine value to order on.
     * @param preferHighestValue Reverses the ordering to output higher values first.
     * @return New {@link EventPrioritizer}
     */
    static <U extends Comparable<U>> EventPrioritizer byKey(
        final String name,
        final Function<TxExecutionEvent, U> keyExtractor,
        final boolean preferHighestValue) {
      final Comparator<TxExecutionEvent> baseComparator = Comparator.comparing(keyExtractor);
      final Comparator<TxExecutionEvent> comparator =
          preferHighestValue ? baseComparator.reversed() : baseComparator;
      return new EventPrioritizer(
          name,
          ls -> {
            Collections.<TxExecutionEvent>sort(ls, comparator);
            return ls.remove(ls.size() - 1);
          });
    }

    /**
     * Create new {@link EventPrioritizer} with a random ordering.
     *
     * @param seed Seed for randomness.
     * @return New {@link EventPrioritizer}
     */
    static EventPrioritizer atRandom(long seed) {
      final Random random = new Random(seed);
      final int firstValue = random.nextInt();
      final String name = "Random(seed=%s, first=%s)".formatted(seed, firstValue);
      return new EventPrioritizer(name, ls -> ls.remove(random.nextInt(ls.size())));
    }
  }

  /** Set of hard-coded prioritizers. */
  private static final List<EventPrioritizer> HARD_CODED_PRIORITIZES =
      List.of(
          new EventPrioritizer("FIFO", ls -> ls.remove(0)),
          new EventPrioritizer("LIFO", ls -> ls.remove(ls.size() - 1)),
          EventPrioritizer.byKey(
              "Least Address", t -> t.event().getEvent().getEvent().getInner().target(), false),
          EventPrioritizer.byKey(
              "Least identifier", t -> t.event().getEvent().getEvent().identifier(), false),
          EventPrioritizer.byKey(
              "Least height", t -> t.event().getEvent().getEvent().getHeight(), false),
          EventPrioritizer.byKey(
              "Most height", t -> t.event().getEvent().getEvent().getHeight(), true));

  /** Result object for performing assertions on the executed event tree. */
  public static final class EventTreeExecutionResult {

    private final JunitContractTest testInstance;
    private final Map<Integer, TxExecutionEvent> executedEvents;
    private final List<TxExecutionEvent> failedEvents;

    private EventTreeExecutionResult(
        JunitContractTest testInstance,
        Map<Integer, TxExecutionEvent> executedEvents,
        List<TxExecutionEvent> failedEvents) {
      this.testInstance = testInstance;
      this.executedEvents = executedEvents;
      this.failedEvents = failedEvents;
    }

    /** Asserts that no failures occured in the event tree. */
    public void assertNoFailures() {
      if (!failedEvents.isEmpty()) {
        throw new RuntimeException(
            formatEventTraceException(testInstance, executedEvents, failedEvents.get(0)));
      }
    }

    /**
     * Asserts that the given failures occured in the event tree.
     *
     * @param contract0 Contract where error occured.
     * @param error0 Substring that must occur in the error.
     * @param contract1 Contract where error occured.
     * @param error1 Substring that must occur in the error.
     */
    public void assertFailures(
        BlockchainAddress contract0, String error0, BlockchainAddress contract1, String error1) {
      assertFailures(List.of(contract0, contract1), List.of(error0, error1));
    }

    private void assertFailures(List<BlockchainAddress> contracts, List<String> errors) {
      Assertions.assertThat(failedEvents).hasSize(2);

      for (int idx = 0; idx < contracts.size(); idx++) {
        Assertions.assertThat(
                failedEvents.get(idx).event().getEvent().getEvent().getInner().target())
            .isEqualTo(contracts.get(idx));
        Assertions.assertThat(failedEvents.get(idx).event().getFailureCause().getErrorMessage())
            .contains(errors.get(idx));
      }
    }
  }
}
