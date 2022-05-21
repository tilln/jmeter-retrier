package nz.co.breakpoint.jmeter.modifiers;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

/** Dummy sampler that fails a specific number of times.
 */
public class FailingSampler extends AbstractSampler {

    int remainingFailures = 0;

    public FailingSampler(int totalFailures) {
        this.remainingFailures = totalFailures;
        setName("Failing Sampler");
    }

    public int getRemainingFailures() {
        return remainingFailures;
    }

    public void setRemainingFailures(int remainingFailures) {
        this.remainingFailures = remainingFailures;
    }

    @Override
    public SampleResult sample(Entry e) {
        SampleResult res = SampleResult.createTestSample(100);
        res.setSampleLabel(getName());
        res.setResponseCode("code"+remainingFailures);
        res.setResponseData("data"+remainingFailures, "UTF-8");
        res.setResponseHeaders("headers"+remainingFailures);
        res.setResponseMessage("message"+remainingFailures);
        res.setSentBytes(remainingFailures);
        res.setHeadersSize(remainingFailures);
        res.setSuccessful(remainingFailures-- <= 0);
        return res;
    }
}
