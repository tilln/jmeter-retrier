# jmeter-retrier [![travis][travis-image]][travis-url]

[travis-image]: https://app.travis-ci.com/tilln/jmeter-retrier.svg?branch=master
[travis-url]: https://app.travis-ci.com/tilln/jmeter-retrier

Overview
--------

This Apache JMeter plugin provides a Post-Processor that retries failed samples.

### Motivation


JMeter provides no mechanism to retry failed samplers.
Sample errors can be ignored (and the thread continues or starts the next loop)
or the thread can be stopped.

Scripts that contain a sequence of user interactions have no easy way to recover from sampler errors,
but loops and conditions have to be explicitly built into the scripts.

For example, stress tests may get throttled
(e.g. [HTTP 429](https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/429))
but the script should continue its flow, however, the throttled request would need to be retried.

### Results Representation 

Retries are added as sub-results,
with the initial, failed attempt as the first sub-result with the original label,
and all retries thereafter.
All retries are labelled with a suffix ("-retry*N*" with _N_ enumerating the attempts).

The main result contains:
- Request and response data of the final attempt, so that assertions can be applied,
- Total byte count of all attempts, 
- Total response time of all attempts (without pauses),
- Label suffix "-retry" (without number).


![Example](docs/example.png)


Usage
-----

### Retry Post-Processor

Simply add a *Retry Post-Processor* to the [Scope](https://jmeter.apache.org/usermanual/test_plan.html#scoping_rules)
of samplers that should be retried.

![Options](docs/retry-postprocessor.png)


#### Retry Conditions:

- *Max Number of Retries*: Positive number limits the number of retries, negative retries infinitely, zero means no retries (default: 0).
- *Response Codes*: Regex of response codes for which to retry, or empty to retry any unsuccessful sample result (default: empty).

#### Delay Settings:

- *Pause (milliseconds)*: How long to pause before retrying the sampler or zero for no pause (default: 0).
- *Backoff*: How to modify the initial *Pause* after each retry, in order to slow down to avoid overload.
  
  |Backoff    |Meaning|n-th Delay|Example|
  |-----------|-------|----------|-------|
  |None       |Constant pause between all retries|Pause • 1|100, 100, 100, 100, 100, 100, 100 ms|
  |Linear     |Pause grows linearly|Pause • n|100, 200, 300, 400, 500, 600, 700 ms|
  |Polynomial |Pause is multiplied with a polynomial factor|Pause • n<sup>2</sup>|100, 400, 900, 1600, 2500, 3600, 4900 ms|
  |Exponential|Pause doubles with every retry|Pause • 2<sup>n</sup>|100, 200, 400, 800, 1600, 3200, 6400 ms|

  The JMeter Property `jmeter.retrier.backoffMultiplier` configures the exponent and base
  of the "Polynomial" and "Exponential" backoff strategies (default: 2).

- *Jitter Factor* (positive decimal): Amount of random variation to add to the pauses (default: 0, i.e. jitter turned off).
  For example, a value of 0.1 will add up to 10% of the *Pause* to the calculated delay.

- *Respect "Retry-After":* Whether to respect an HTTP response header "Retry-After" before retrying (default: False).
If a non-zero *Pause* is defined as well, the greater of the two values will be used.  

Note that this plugin will not [execute](https://jmeter.apache.org/usermanual/test_plan.html#executionorder)
other JMeter elements, i.e. Pre-Processors, Timers, Post-Processors and Assertions as part of a retry.

Pre-Processors and Timers will only be executed **once**, before the initial attempt.
Likewise, Post-Processors and Assertions will only be executed **once**, after the final attempt
(or before, in case of Post-Processors that appear before the *Retry Post-Processor*).


### JMeter Properties
The following properties control the plugin behaviour:

- `jmeter.retrier.sampleLabelSuffix`:
  Suffix to append to the retried sample's label (default: "-retry").
- `jmeter.retrier.backoffMultiplier`: Factor that determines how much to the pauses increase with each retry,
  as a base or exponent for polynomial/exponential backoff (default: 2).

Installation
------------
<!--
### Via [PluginsManager](https://jmeter-plugins.org/wiki/PluginsManager/)

Under tab "Available Plugins", select "Sample Retrier", then click "Apply Changes and Restart JMeter".

### Via Package from [JMeter-Plugins.org](https://jmeter-plugins.org/)

Extract the [zip package](https://jmeter-plugins.org/files/packages/tilln-retrier-1.0.zip) into JMeter's lib directory, then restart JMeter.
-->
### Via Manual Download

1. Copy the [jmeter-retrier jar file](https://github.com/tilln/jmeter-retrier/releases/download/1.0-SNAPSHOT/jmeter-retrier-1.0-SNAPSHOT.jar) into JMeter's lib/ext directory.
2. Restart JMeter.


Limitations
-----------

- Minimum JMeter version 5.0
- Pre-Processors, Timers, other Post-Processors, and Assertions are **not** executed when retrying, but only once (before the first attempt or after the last)
- Connect Time, Latency and Idle Time are not accumulated
