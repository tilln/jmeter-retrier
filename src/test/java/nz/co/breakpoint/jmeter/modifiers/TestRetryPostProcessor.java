package nz.co.breakpoint.jmeter.modifiers;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

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
        instance.setResponseCodes("3|2"); // retry the first two results
        instance.process();
        assertEquals("Expect three sub-results", 3, prev.getSubResults().length);
        assertEquals("Expect failure response code", "3", prev.getSubResults()[0].getResponseCode());
        assertEquals("Expect failure response code", "2", prev.getSubResults()[1].getResponseCode());
        assertEquals("Expect success response code", "1", prev.getSubResults()[2].getResponseCode());
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
        long totalBytes = Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getBytesAsLong).sum();
        assertEquals("Bytes total mismatch", prev.getBytesAsLong(), totalBytes);
        long totalTime = Arrays.stream(prev.getSubResults()).mapToLong(SampleResult::getTime).sum();
        assertEquals("Response time total mismatch", prev.getTime(), totalTime);
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
}
