package nz.co.breakpoint.jmeter.modifiers;

import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

/** Dummy sampler that mimics Redirect results
 */
public class RedirectingSampler extends FailingSampler {

    public RedirectingSampler(int totalFailures) {
        super(totalFailures);
    }

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult main = SampleResult.createTestSample(100),
            first = SampleResult.createTestSample(100),
            second = SampleResult.createTestSample(100);
        first.setResponseCode("301");
        if (remainingFailures-- > 0) {
            second.setResponseCode("404");
        } else {
            second.setResponseCode("200");
            second.setSuccessful(true);
            main.setSuccessful(true);
        }
        main.addSubResult(first, true);
        main.addSubResult(second, true);
        return main;
    }
}
