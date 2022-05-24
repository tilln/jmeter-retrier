package nz.co.breakpoint.jmeter.modifiers;

import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class RetryPostProcessor extends AbstractTestElement implements PostProcessor, TestBean {

    private static final long serialVersionUID = 1L;

    public static Logger log = LoggerFactory.getLogger(RetryPostProcessor.class);

    public static final String
            MAX_RETRIES = "maxRetries",
            PAUSE_MILLISECONDS = "pauseMilliseconds",
            BACKOFF = "backoff",
            JITTER = "jitter",
            RESPONSE_PART = "responsePart",
            ERROR_PATTERN = "errorPattern",
            RETRY_AFTER = "retryAfter";

    public static final String
            SAMPLE_LABEL_SUFFIX_PROPERTY = "jmeter.retrier.sampleLabelSuffix",
            SAMPLE_LABEL_SUFFIX_PROPERTY_DEFAULT = "-retry",
            BACKOFF_MULTIPLIER_PROPERTY = "jmeter.retrier.backoffMultiplier";

    public static final Pattern RETRY_AFTER_HEADER_PATTERN = Pattern.compile("\\bRetry-After: (\\V*)"); // word boundary/non-vertical whitespace

    @Override
    public void process() {
        long retries = getMaxRetries();
        if (retries == 0) {
            log.debug("Retries turned off");
            return;
        }
        JMeterContext context = getThreadContext();
        Sampler sampler = context.getCurrentSampler();
        final SampleResult prev = context.getPreviousResult(); // reference can't be changed
        SampleResult res = prev;

        for (int i = 1; retries < 0 || retries-- > 0; i++) {
            if (!isRetryCondition(sampler, res)) {
                log.debug("Not retrying sampler \"{}\"", sampler.getName());
                return;
            }
            if (pause(prev, i)) return; // interrupted?

            log.info("Retrying sampler \"{}\" (retry {})", sampler.getName(), i);
            res = sampler.sample(null);
            res = modifySampleResult(i, res, prev);
        }
    }

    protected boolean isRetryCondition(Sampler sampler, SampleResult lastResult) {
        ResponsePart part = ResponsePart.fromTag(getResponsePart());
        String responsePart = part.extractPart(lastResult);
        final String errorPattern = getErrorPattern();
        if (errorPattern != null && !errorPattern.isEmpty()) {
            try {
                Pattern pattern = Pattern.compile(errorPattern);
                Matcher matcher = pattern.matcher(responsePart);
                final boolean doRetry = matcher.find();
                log.debug("Response part {} retry condition", doRetry ? "matches" : "does not match");
                return doRetry;
            } catch (PatternSyntaxException e) {
                log.error("Ignoring invalid error pattern {}", errorPattern, e);
                return false;
            }
        }
        return !lastResult.isSuccessful();
    }

    /**
     * @return true iff interrupted during pause
     */
    protected boolean pause(SampleResult result, int retry) {
        long pause = BackoffType.fromTag(getBackoff())
                .nextPause(getPauseMilliseconds(), retry, getJitter());

        if (getRetryAfter()) {
            long retryAfter = getDelayUntilRetryAfterHeader(result);
            if (retryAfter != 0) {
                pause = Math.max(pause, retryAfter);
            }
        }
        if (pause > 0) {
            log.debug("Waiting {}ms", pause);
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                log.warn("Retry pause interrupted");
                return true;
            }
        }
        return false;
    }

    /** Add current retry as sub-result to list of previous results.
     */
    protected SampleResult modifySampleResult(int retryCount, SampleResult retry, SampleResult prev) {
        if (retryCount == 1) { // this is the first retry
            SampleResult firstTry = new SampleResult(prev); // clone
            prev.removeSubResults(); // in case there are any (e.g. redirects)
            // reset all counters as well as data to avoid double counting the first try
            prev.setBodySize(0L);
            prev.setHeadersSize(0);
            prev.setSentBytes(0);
            prev.setBytes(0L);
            prev.setResponseData(new byte[0]);
            log.debug("Adding original result "+firstTry.getSampleLabel());
            prev.addSubResult(firstTry, false);
            final String suffix = JMeterUtils.getPropDefault(SAMPLE_LABEL_SUFFIX_PROPERTY, SAMPLE_LABEL_SUFFIX_PROPERTY_DEFAULT);
            prev.setSampleLabel(prev.getSampleLabel()+suffix);
        }
        final String retryLabel = prev.getSampleLabel()+retryCount;

        log.debug("Adding latest retry "+retryLabel);
        long originalEndTime = prev.getEndTime();
        prev.addSubResult(retry, false);
        // This will set the end time to the latest result's end time rather than adding them up,
        // and it will add up setBytes, setSentBytes, setHeadersSize, setBodySize,
        // so we need to manually add the end times:
        prev.setEndTime(originalEndTime+retry.getTime());

        // Subresult label may get modified when adding so fix it afterwards:
        retry.setSampleLabel(retryLabel);

        // Other timings are not added up by addSubResult:
        prev.setConnectTime(prev.getConnectTime()+retry.getConnectTime());
        prev.setIdleTime(prev.getIdleTime()+retry.getIdleTime());
        prev.setLatency(prev.getLatency()+retry.getLatency());

        // Copy all remaining attributes into main result:
        prev.setSuccessful(retry.isSuccessful());
        prev.setContentType(retry.getContentType());
        prev.setResponseCode(retry.getResponseCode());
        prev.setResponseData(retry.getResponseData());
        prev.setResponseHeaders(retry.getResponseHeaders());
        prev.setResponseMessage(retry.getResponseMessage());

        return retry;
    }

    /**
     * @return milliseconds until the time specified in the header, or 0 in case of no header or already in the past.
     */
    public static long getDelayUntilRetryAfterHeader(SampleResult result) {
        final Matcher m = RETRY_AFTER_HEADER_PATTERN.matcher(result.getResponseHeaders());
        if (m.find()) {
            final String value = m.group(1);
            log.debug("Received \"Retry-After\": {}", value);

            if (value.matches("^[0-9]+$")) {
                return Long.parseLong(value) * 1000L; // seconds to millis
            }
            try {
                long millis = Instant.now().until(
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME),
                        ChronoUnit.MILLIS);
                return millis > 0 ? millis : 0;
            } catch (DateTimeParseException e) {
                log.warn("Ignoring invalid Retry-After header value \"{}\"", value);
            }
        }
        return 0;
    }

    public long getMaxRetries() { return getPropertyAsLong(MAX_RETRIES); }
    public void setMaxRetries(long maxRetries) { setProperty(MAX_RETRIES, maxRetries); }

    public long getPauseMilliseconds() { return getPropertyAsLong(PAUSE_MILLISECONDS); }
    public void setPauseMilliseconds(long pauseMilliseconds) { setProperty(PAUSE_MILLISECONDS, pauseMilliseconds); }

    public String getResponsePart() { return getPropertyAsString(RESPONSE_PART); }
    public void setResponsePart(String responsePart) { setProperty(RESPONSE_PART, responsePart); }

    public String getErrorPattern() { return getPropertyAsString(ERROR_PATTERN); }
    public void setErrorPattern(String errorPattern) { setProperty(ERROR_PATTERN, errorPattern); }

    public String getBackoff() { return getPropertyAsString(BACKOFF); }
    public void setBackoff(String backoff) { setProperty(BACKOFF, backoff); }

    public double getJitter() { return getPropertyAsDouble(JITTER); }
    public void setJitter(double jitter) { setProperty(new DoubleProperty(JITTER, jitter)); }

    public boolean getRetryAfter() { return getPropertyAsBoolean(RETRY_AFTER); }
    public void setRetryAfter(boolean retryAfter) { setProperty(RETRY_AFTER, retryAfter); }

    public enum ResponsePart {
        NONE,
        RESPONSE_CODE {
            @Override
            public String extractPart(SampleResult result) {
                return result.getResponseCode();
            }
        },
        RESPONSE_DATA {
            @Override
            public String extractPart(SampleResult result) {
                return result.getResponseDataAsString();
            }
        },
        RESPONSE_HEADERS {
            @Override
            public String extractPart(SampleResult result) {
                return result.getResponseHeaders();
            }
        },
        RESPONSE_MESSAGE {
            @Override
            public String extractPart(SampleResult result) {
                return result.getResponseMessage();
            }
        };

        public String extractPart(SampleResult result) {
            return null;
        }

        // Tags must match ResourceBundle and appear in script files:
        public static ResponsePart fromTag(String responsePart) {
            return responsePart == null || responsePart.isEmpty() ? NONE :
                    valueOf(responsePart.replaceFirst(RESPONSE_PART + ".", ""));
        }

        public static String[] tags() {
            return Arrays.stream(ResponsePart.values()).map(ResponsePart::toTag).toArray(String[]::new);
        }

        public String toTag() {
            return RESPONSE_PART + "." + this;
        }
    }

    public enum BackoffType {
        NONE,
        LINEAR {
            @Override
            public long nextPause(long pause, int retry, double jitter) {
                return pause*retry + addJitter(pause, jitter);
            }
        },
        POLYNOMIAL {
            @Override
            public long nextPause(long pause, int retry, double jitter) {
                return Math.round(pause * Math.pow(retry, multiplier)) + addJitter(pause, jitter);
            }
        },
        EXPONENTIAL {
            @Override
            public long nextPause(long pause, int retry, double jitter) {
                return Math.round(pause * Math.pow(multiplier, retry-1)) + addJitter(pause, jitter);
            }
        };
        static double multiplier = JMeterUtils.getPropDefault(BACKOFF_MULTIPLIER_PROPERTY, 2.0f);

        public long nextPause(long pause, int retry, double jitter) {
            return pause + addJitter(pause, jitter);
        }

        public long addJitter(long pause, double jitterFactor) {
            return (jitterFactor == 0.0d) ? 0 :
                Math.round(pause * ThreadLocalRandom.current().nextDouble(Math.abs(jitterFactor)));
        }

        // Tags must match ResourceBundle and appear in script files:
        public static BackoffType fromTag(String backoffType) {
            return backoffType == null || backoffType.isEmpty() ? NONE :
                    valueOf(backoffType.replaceFirst(BACKOFF + ".", ""));
        }

        public static String[] tags() {
            return Arrays.stream(BackoffType.values()).map(BackoffType::toTag).toArray(String[]::new);
        }

        public String toTag() {
            return BACKOFF + "." + this;
        }
    }
}
