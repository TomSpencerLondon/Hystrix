### Hystrix overview

Distributed systems such as microservices necessarily involve many services collaborating.
The services communicate over a network and this can lead to failure or delayed responses.
If the service fails it can impact other services and this can affect performance, availability and in worse cases
can bring down the whole system. Hystrix is a framework to ensure that applications are resilient and fault-tolerant.

Hystrix isolates the failing services and stops cascading failures. 

We start by creating a simple HystrixCommand - CommandHelloWorld which extends HystrixCommand:

```java
class CommandHelloWorld extends HystrixCommand<String> {

    private String name;

    CommandHelloWorld(String name) {
        super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        this.name = name;
    }

    @Override
    protected String run() {
        return "Hello " + name + "!";
    }
}
```

We then add a test to ensure that it works:
```java
class CommandHelloWorldTest {

    @Test
    void givenInputBobAndDefaultSettings_whenCommandExecuted_thenReturnHelloBob() {
        assertThat(new CommandHelloWorld("Bob").execute())
                .isEqualTo("Hello Bob!");
    }
}
```

#### Setting up a Remote Service
We are now going to try to simulate a real world example. We will create a RemoteServiceTestSimulator to represent a service on a remote server.
It has a method which responds with a message after a period of time. The wait is a simulation of the process of calling a remote system with a delayed response:

```java
class RemoteServiceTestSimulator {

    private long wait;

    RemoteServiceTestSimulator(long wait) throws InterruptedException {
        this.wait = wait;
    }

    String execute() throws InterruptedException {
        Thread.sleep(wait);
        return "Success";
    }
}
```

We also add a client that calls the RemoteServiceTestSimulator. The call to the service is wrapped in the run() method of a 
HystrixCommand. The wrapping gives us the resilience we required above:
```java
class RemoteServiceTestCommand extends HystrixCommand<String> {

    private RemoteServiceTestSimulator remoteService;

    RemoteServiceTestCommand(Setter config, RemoteServiceTestSimulator remoteService) {
        super(config);
        this.remoteService = remoteService;
    }

    @Override
    protected String run() throws Exception {
        return remoteService.execute();
    }
}
```

We can now add a test for this RemoteTestCommand:
```java

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
}
```

### Remote Service deterioration and Defensive Programming
We will now look at how Hystrix can help us with situations when the remote service starts deteriorating.
We will first look at how to set a timeout on HystrixCommand and how it helps stop short-circuiting:

```text
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
```
The test above ensures that when the execution timeout is less than the service timeout the result is success.

We also test for when the execution timeout is more than the service timeout:
```text
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
```

We can also set a thread-pool to ensure that the service does not continue to call the remote service indefinitely:
```text
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
```
We can further improve the defensive programming with a short circuit breaker pattern. If the remote service has started failing we want to stop making requests for a certain amount of time.
This is the short circuit breaker pattern:

```text

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
```


With the above settings in place, our HystrixCommand will now trip open after two failed request. 
The third request will not even hit the remote service even though we have set the service delay to be 500 ms, Hystrix will short circuit and our method will return null as the response.

### Hystrix Summary
Hystrix helps:
1. Protect and control failures and latency
2. Stop cascading failures
3. Fail fast and recover
4. Degrade gracefully where possible
5. Real time monitoring and alerting of command center when services fail

