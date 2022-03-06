package nz.co.breakpoint.jmeter.modifiers;

import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RetryPostProcessor extends AbstractTestElement implements PostProcessor, TestBean {

    private static final long serialVersionUID = 1L;

    public static Logger log = LoggerFactory.getLogger(RetryPostProcessor.class);

    public static final String
            MAX_RETRIES = "maxRetries",
            PAUSE_MILLISECONDS = "pauseMilliseconds",
            RESPONSE_CODES = "responseCodes";

    public static final String
            SAMPLE_LABEL_SUFFIX_PROPERTY = "jmeter.retrier.sampleLabelSuffix",
            SAMPLE_LABEL_SUFFIX_PROPERTY_DEFAULT = "-retry";

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
            if (pause(prev)) return;

            log.debug("Retrying sampler \"{}\" (retry {})", sampler.getName(), i);
            res = sampler.sample(null);
            res = modifySampleResult(i, res, prev);
        }
    }

    protected boolean isRetryCondition(Sampler sampler, SampleResult lastResult) {
        final String retryCodes = getResponseCodes();
        if (retryCodes != null && !retryCodes.isEmpty()) {
            final String rc = lastResult.getResponseCode();
            log.debug("Checking whether last sample response code \"{}\" is to be retried", rc);
            return rc.matches(retryCodes);
        }
        log.debug("Checking whether last sample was successful: {}", lastResult.isSuccessful());
        return !lastResult.isSuccessful();
    }

    protected boolean pause(SampleResult result) {
        long pause = getPauseMilliseconds();
        if (pause > 0) {
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

    public static String extractRetryAfterHeader(SampleResult result) {
        Pattern p = Pattern.compile("Retry-After\\s*:\\s*([0-9]+)");
        Matcher m = p.matcher(result.getResponseHeaders());
        return m.matches() ? m.group(1) : null;
    }

    public long getMaxRetries() { return getPropertyAsLong(MAX_RETRIES); }
    public void setMaxRetries(long maxRetries) { setProperty(MAX_RETRIES, maxRetries); }

    public long getPauseMilliseconds() { return getPropertyAsLong(PAUSE_MILLISECONDS); }
    public void setPauseMilliseconds(long pauseMilliseconds) { setProperty(PAUSE_MILLISECONDS, pauseMilliseconds); }

    public String getResponseCodes() { return getPropertyAsString(RESPONSE_CODES); }
    public void setResponseCodes(String responseCodes) { setProperty(RESPONSE_CODES, responseCodes); }
}
