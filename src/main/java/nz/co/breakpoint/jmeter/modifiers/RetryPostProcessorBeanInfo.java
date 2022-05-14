package nz.co.breakpoint.jmeter.modifiers;

import java.beans.PropertyDescriptor;
import org.apache.jmeter.testbeans.BeanInfoSupport;
import org.apache.jmeter.testbeans.gui.TypeEditor;

import static nz.co.breakpoint.jmeter.modifiers.RetryPostProcessor.*;

public class RetryPostProcessorBeanInfo extends BeanInfoSupport {

    public RetryPostProcessorBeanInfo() {
        super(RetryPostProcessor.class);

        createPropertyGroup("Options", new String[]{
                MAX_RETRIES, PAUSE_MILLISECONDS, BACKOFF, RESPONSE_CODES, RETRY_AFTER
        });
        PropertyDescriptor p;

        p = property(MAX_RETRIES);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, 0L);

        p = property(PAUSE_MILLISECONDS);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, 0L);

        p = property(BACKOFF, TypeEditor.ComboStringEditor);
        p.setValue(RESOURCE_BUNDLE, getBeanDescriptor().getValue(RESOURCE_BUNDLE));
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, RetryPostProcessor.BackoffType.NONE.toTag());
        p.setValue(TAGS, RetryPostProcessor.BackoffType.tags());

        p = property(RESPONSE_CODES);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, "");

        p = property(RETRY_AFTER);
        p.setValue(NOT_UNDEFINED, Boolean.TRUE);
        p.setValue(DEFAULT, Boolean.FALSE);
    }
}
