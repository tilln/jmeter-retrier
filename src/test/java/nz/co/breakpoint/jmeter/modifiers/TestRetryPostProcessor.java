package nz.co.breakpoint.jmeter.modifiers;

import nz.co.breakpoint.jmeter.modifiers.RetryPostProcessor.BackoffType;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.IntStream;
import static org.junit.Assert.*;

public class TestRetryPostProcessor {
    protected JMeterContext context;
    protected FailingSampler sampler;
    protected SampleResult prev;
    protected RetryPostProcessor instance;

    @Before
    public void setUp() {
        context = JMeterContextService.getContext();
        sampler = new FailingSampler(3);
        context.setCurrentSampler(sampler);

        instance = new RetryPostProcessor();
        instance.setThreadContext(context);
        instance.setMaxRetries(sampler.getRemainingFailures());

        prev = sampler.sample(null);
        context.setPreviousResult(prev);
    }

    @Test
    public void itShouldNotModifyResultForZeroRetries() {
        instance.setMaxRetries(0);
        instance.process();
        assertEquals("Original result must be unmodified", prev, context.getPreviousResult());
        assertEquals("Expect no sub-results", 0, prev.getSubResults().length);
    }

    @Test
    public void itShouldRetryUntilSampleSuccess() {
        instance.setMaxRetries(-1);
        instance.process();
        assertEquals("Expect one original and 3 retried sub-results", 4, prev.getSubResults().length);
    }

    @Test
    public void itShouldNotExceedMaxRetries() {
        instance.setMaxRetries(1);
        instance.process();
        assertEquals("Expect two sub-results", 2, prev.getSubResults().length);
    }

    @Test
    public void itShouldRetryOnlySpecifiedResponseCodes() {
        instance.setMaxRetries(10);
        instance.setResponseCodes("[a-z]*[32]"); // retry the first two results
        instance.process();
        assertEquals("Expect three sub-results", 3, prev.getSubResults().length);
        assertEquals("Expect failure response code", "code3", prev.getSubResults()[0].getResponseCode());
        assertEquals("Expect failure response code", "code2", prev.getSubResults()[1].getResponseCode());
        assertEquals("Expect success response code", "code1", prev.getSubResults()[2].getResponseCode());
        assertTrue("Expect all samples to be unsuccessful", Arrays.stream(prev.getSubResults()).noneMatch(SampleResult::isSuccessful));
    }

    @Test
    public void itShouldAppendSuffixToResultLabel() {
        instance.setMaxRetries(1);
        instance.process();
        assertEquals("Expect two sub-results", 2, prev.getSubResults().length);
        final String name = sampler.getName();
        assertEquals("Expect main result suffix", name + "-retry", prev.getSampleLabel());
        assertEquals("Expect original label", name, prev.getSubResults()[0].getSampleLabel());
        assertEquals("Expect sub-result suffix", name + "-retry1", prev.getSubResults()[1].getSampleLabel());
    }

    @Test
    public void itShouldAccumulateTimingsAndByteCounts() {
        instance.setMaxRetries(1);
        instance.process();
        assertEquals("Expect two sub-results", 2, prev.getSubResults().length);
        assertEquals("Bytes total mismatch", prev.getBytesAsLong(),
                Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getBytesAsLong).sum());
        assertEquals("Sent Bytes total mismatch", prev.getSentBytes(),
                Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getSentBytes).sum());
        assertEquals("Header Size total mismatch", prev.getHeadersSize(),
                Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getHeadersSize).sum());
        assertEquals("Body Size total mismatch", prev.getBodySizeAsLong(),
                Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getBodySizeAsLong).sum());
        assertEquals("Response time total mismatch", prev.getTime(),
                Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getTime).sum());
    }

    @Test
    public void itShouldWaitBetweenRetries() {
        instance.setPauseMilliseconds(100);
        Instant start = Instant.now();
        instance.process();
        long duration = Duration.between(start, Instant.now()).toMillis();
        assertEquals("Expect four sub-results", 4, prev.getSubResults().length);
        assertTrue("Expect at least 3 pauses", duration >= 300);
    }

    @Test
    public void itShouldHaveDifferentBackoffStrategies() {
        assertArrayEquals(new long[]{ 100, 100, 100, 100, 100, 100, 100 },
                IntStream.rangeClosed(1, 7).mapToLong(i -> BackoffType.NONE.nextPause(100, i, 0.0)).toArray());
        assertArrayEquals(new long[]{ 100, 200, 300, 400, 500, 600, 700 },
                IntStream.rangeClosed(1, 7).mapToLong(i -> BackoffType.LINEAR.nextPause(100, i, 0.0)).toArray());
        assertArrayEquals(new long[]{ 100, 400, 900, 1600, 2500, 3600, 4900 },
                IntStream.rangeClosed(1, 7).mapToLong(i -> BackoffType.POLYNOMIAL.nextPause(100, i, 0.0)).toArray());
        assertArrayEquals(new long[]{ 100, 200, 400, 800, 1600, 3200, 6400 },
                IntStream.rangeClosed(1, 7).mapToLong(i -> BackoffType.EXPONENTIAL.nextPause(100, i, 0.0)).toArray());
    }

    @Test
    public void itShouldApplyJitter() {
        assertTrue(IntStream.rangeClosed(1, 7).mapToLong(i -> BackoffType.NONE.nextPause(100, i, 1.0))
                .anyMatch(l -> l != 100));
    }

    @Test
    public void itShouldBackoffExponentially() {
        instance.setPauseMilliseconds(100);
        instance.setBackoff(BackoffType.EXPONENTIAL.toTag());
        Instant start = Instant.now();
        instance.process();
        long duration = Duration.between(start, Instant.now()).toMillis();
        assertEquals("Expect four sub-results", 4, prev.getSubResults().length);
        assertTrue("Expect increasing pauses", duration >= 100+200+400);
    }

    @Test
    public void itShouldPreserveNestedSubResults() {
        sampler = new RedirectingSampler(1);
        context.setCurrentSampler(sampler);
        prev = sampler.sample(null);
        context.setPreviousResult(prev);
        instance.process();
        assertEquals("Expect original and retry sub-results", 2, prev.getSubResults().length);
        SampleResult failure = prev.getSubResults()[0],
            success = prev.getSubResults()[1];
        assertEquals("Expect failed redirect", 2, failure.getSubResults().length);
        assertFalse("Expect failure", failure.isSuccessful());
        assertEquals("Expect redirect sub-result", "301", failure.getSubResults()[0].getResponseCode());
        assertEquals("Expect redirected sub-result", "404", failure.getSubResults()[1].getResponseCode());
        assertEquals("Expect successful redirect", 2, success.getSubResults().length);
        assertTrue("Expect success", success.isSuccessful());
        assertEquals("Expect redirect sub-result", "301", success.getSubResults()[0].getResponseCode());
        assertEquals("Expect redirected sub-result", "200", success.getSubResults()[1].getResponseCode());
    }

    @Test
    public void itShouldAllowOverridingOfResultModification() {
        instance = new RetryPostProcessor() {
            @Override
            protected SampleResult modifySampleResult(int retryCount, SampleResult retry, SampleResult prev) {
                prev.addRawSubResult(retry);
                return retry;
            }
        };
        instance.setMaxRetries(1);
        instance.process();
        assertEquals("Expect one sub-result", 1, prev.getSubResults().length);
        assertEquals("Expect original label", sampler.getName(), prev.getSampleLabel());
    }

    @Test
    public void itShouldAllowOverridingOfRetryCondition() {
        instance = new RetryPostProcessor() {
            @Override
            protected boolean isRetryCondition(Sampler sampler, SampleResult lastResult) {
                return false;
            }
        };
        instance.process();
        assertEquals("Expect no sub-results", 0, prev.getSubResults().length);
    }

    @Test
    public void itShouldParseAnyRetryAfterHeader() {
        prev.setResponseHeaders("HTTP/1.1 301 OK\n" +
                "Retry-After: INVALID\n"
        );
        assertEquals(0, instance.getDelayUntilRetryAfterHeader(prev));

        prev.setResponseHeaders("HTTP/1.1 429 OK\n" +
                "Retry-After: 123"
        );
        assertEquals(123000, instance.getDelayUntilRetryAfterHeader(prev));

        final String fiveSecondsFromNow = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneId.of("GMT")).plusSeconds(5));
        prev.setResponseHeaders("HTTP/1.1 503 OK\n" +
                "Retry-After: " + fiveSecondsFromNow + "\n"
        );
        assertTrue("At most 5000 milliseconds", 5000 >= instance.getDelayUntilRetryAfterHeader(prev));

        prev.setResponseHeaders("Retry-After: Thu, 01 Jan 1970 00:00:00 GMT\n");
        assertEquals("Expect zero if in the past", 0, instance.getDelayUntilRetryAfterHeader(prev));
    }

    @Test
    public void itShouldRespectRetryAfterHeader() {
        instance.setRetryAfter(true);
        instance.setMaxRetries(1);
        prev.setResponseHeaders("\nRetry-After: 3");
        Instant start = Instant.now();
        instance.process();
        long duration = Duration.between(start, Instant.now()).toMillis();
        assertTrue("Expect at least 3 sec pause", duration >= 3000);
    }
}
