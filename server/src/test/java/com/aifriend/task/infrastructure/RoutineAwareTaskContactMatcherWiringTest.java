package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.aifriend.task.application.TaskContactMatcherPort;
import com.aifriend.task.application.TaskIntentPort;
import com.aifriend.task.application.TaskRoutineCommandMatcherPort;

class RoutineAwareTaskContactMatcherWiringTest {

    @Test
    void primaryDecoratorMustReceiveNamedDelegateWithoutBeanAmbiguity() {
        TaskContactMatcherPort delegate = mock(TaskContactMatcherPort.class);
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "mfccDtwTaskContactMatcherAdapter",
                    TaskContactMatcherPort.class,
                    () -> delegate);
            context.registerBean(TaskIntentPort.class,
                    () -> mock(TaskIntentPort.class));
            context.registerBean(TaskRoutineCommandMatcherPort.class,
                    () -> mock(TaskRoutineCommandMatcherPort.class));
            context.register(RoutineAwareTaskContactMatcherAdapter.class);
            context.refresh();

            assertSame(
                    context.getBean(RoutineAwareTaskContactMatcherAdapter.class),
                    context.getBean(TaskContactMatcherPort.class));
        }
    }
}
