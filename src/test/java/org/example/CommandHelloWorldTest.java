package org.example;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandHelloWorldTest {

    @Test
    void givenInputBobAndDefaultSettings_whenCommandExecuted_thenReturnHelloBob() {
        assertThat(new CommandHelloWorld("Bob").execute())
                .isEqualTo("Hello Bob!");
    }


    @Test
    void givenSvcTimeoutOf100AndDefaultSettings_whenRemoteSvcExecuted_thenReturnSuccess() throws InterruptedException {
        HystrixCommand.Setter config = HystrixCommand
                .Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("RemoteServiceGroup2"));

        assertThat(new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(100)).execute())
                .isEqualTo("Success");
    }

    @Test
    void givenSvcTimeoutOf5000AndExecTimeoutOf10000_whenRemoteSvcExecuted_thenReturnSuccess() throws InterruptedException {
        HystrixCommand.Setter config = HystrixCommand
                .Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("RemoteServiceGroupTest4"));

        HystrixCommandProperties.Setter commandProperties = HystrixCommandProperties.Setter();
        commandProperties.withExecutionTimeoutInMilliseconds(10_000);
        config.andCommandPropertiesDefaults(commandProperties);

        assertThat(new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(500)).execute())
                .isEqualTo("Success");
    }

    @Test
    public void givenSvcTimeoutOf15000AndExecTimeoutOf5000_whenRemoteSvcExecuted_thenExpectHre()
            throws InterruptedException {

        HystrixCommand.Setter config = HystrixCommand
                .Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("RemoteServiceGroupTest5"));

        HystrixCommandProperties.Setter commandProperties = HystrixCommandProperties.Setter();
        commandProperties.withExecutionTimeoutInMilliseconds(5_000);
        config.andCommandPropertiesDefaults(commandProperties);

        assertThatThrownBy(() -> new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(15_000)).execute()).isInstanceOf(HystrixRuntimeException.class);
    }

    @Test
    public void givenSvcTimeoutOf500AndExecTimeoutOf10000AndThreadPool_whenRemoteSvcExecuted_thenReturnSuccess() throws InterruptedException {

        HystrixCommand.Setter config = HystrixCommand
                .Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("RemoteServiceGroupThreadPool"));

        HystrixCommandProperties.Setter commandProperties = HystrixCommandProperties.Setter();
        commandProperties.withExecutionTimeoutInMilliseconds(10_000);
        config.andCommandPropertiesDefaults(commandProperties);
        config.andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter()
                .withMaxQueueSize(10)
                .withCoreSize(3)
                .withQueueSizeRejectionThreshold(10));

        assertThat(new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(500)).execute())
                .isEqualTo("Success");
    }

    @Test
    public void givenCircuitBreakerSetup_whenRemoteSvcCmdExecuted_thenReturnSuccess()
            throws InterruptedException {

        HystrixCommand.Setter config = HystrixCommand
                .Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("RemoteServiceGroupCircuitBreaker"));

        HystrixCommandProperties.Setter properties = HystrixCommandProperties.Setter();
        properties.withExecutionTimeoutInMilliseconds(1000);
        properties.withCircuitBreakerSleepWindowInMilliseconds(4000);
        properties.withExecutionIsolationStrategy
                (HystrixCommandProperties.ExecutionIsolationStrategy.THREAD);
        properties.withCircuitBreakerEnabled(true);
        properties.withCircuitBreakerRequestVolumeThreshold(1);

        config.andCommandPropertiesDefaults(properties);
        config.andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter()
                .withMaxQueueSize(1)
                .withCoreSize(1)
                .withQueueSizeRejectionThreshold(1));

        assertThat(invokeRemoteService(config, 10_000)).isNull();
        assertThat(invokeRemoteService(config, 10_000)).isNull();
        assertThat(invokeRemoteService(config, 10_000)).isNull();

        Thread.sleep(5000);

        assertThat(new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(500)).execute()).isEqualTo("Success");

        assertThat(new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(500)).execute()).isEqualTo("Success");

        assertThat(new RemoteServiceTestCommand(config, new RemoteServiceTestSimulator(500)).execute()).isEqualTo("Success");
    }


    public String invokeRemoteService(HystrixCommand.Setter config, int timeout)
            throws InterruptedException {
        String response = null;
        try {
            response = new RemoteServiceTestCommand(config,
                    new RemoteServiceTestSimulator(timeout)).execute();
        } catch (HystrixRuntimeException ex) {
            System.out.println("ex = " + ex);
        }
        return response;
    }
}