package nz.co.breakpoint.jmeter.modifiers;

import java.beans.PropertyDescriptor;
import org.apache.jmeter.testbeans.BeanInfoSupport;
import static nz.co.breakpoint.jmeter.modifiers.RetryPostProcessor.*;

public class RetryPostProcessorBeanInfo extends BeanInfoSupport {

    public RetryPostProcessorBeanInfo() {
        super(RetryPostProcessor.class);

        createPropertyGroup("Options", new String[]{
                MAX_RETRIES, PAUSE_MILLISECONDS, RESPONSE_CODES,
        });
        PropertyDescriptor p;

        p = property(MAX_RETRIES);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, 0L);

        p = property(PAUSE_MILLISECONDS);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, 0L);

        p = property(RESPONSE_CODES);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, "");
    }
}
