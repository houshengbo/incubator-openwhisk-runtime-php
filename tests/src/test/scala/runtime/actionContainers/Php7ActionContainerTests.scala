/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package runtime.actionContainers

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.WskActorSystem
import actionContainers.{ActionContainer, BasicActionRunnerTests}
import actionContainers.ActionContainer.withContainer
import actionContainers.ResourceHelpers.ZipBuilder
import spray.json._

@RunWith(classOf[JUnitRunner])
abstract class Php7ActionContainerTests extends BasicActionRunnerTests with WskActorSystem {
  // note: "out" will not be empty as the PHP web server outputs a message when it starts up
  val enforceEmptyOutputStream = false

  lazy val phpContainerImageName: String = ???

  override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
    withContainer(phpContainerImageName, env)(code)
  }

  def withPhp7Container(code: ActionContainer => Unit) = withActionContainer()(code)

  behavior of phpContainerImageName

  override val testNoSourceOrExec = TestConfig("")

  override val testNotReturningJson = {
    TestConfig(
      """
       |<?php
       |function main(array $args) {
       |    return "not a json object";
       |}
     """.stripMargin,
      enforceEmptyOutputStream = enforceEmptyOutputStream,
      enforceEmptyErrorStream = false)
  }

  override val testInitCannotBeCalledMoreThanOnce = {
    TestConfig(
      """
        |<?php
        |function main(array $args) : array {
        |    return $args;
        |}
      """.stripMargin,
      enforceEmptyOutputStream = enforceEmptyOutputStream)
  }

  override val testEntryPointOtherThanMain = {
    TestConfig(
      """
        | <?php
        | function niam(array $args) {
        |     return $args;
        | }
      """.stripMargin,
      main = "niam",
      enforceEmptyOutputStream = enforceEmptyOutputStream)
  }

  override val testEcho = {
    TestConfig("""
                 |<?php
                 |function main(array $args) : array {
                 |    echo 'hello stdout';
                 |    error_log('hello stderr');
                 |    return $args;
                 |}
               """.stripMargin)
  }

  override val testUnicode = {
    TestConfig("""
         |<?php
         |function main(array $args) : array {
         |    $str = $args['delimiter'] . " ☃ " . $args['delimiter'];
         |    echo $str . "\n";
         |    return  ["winter" => $str];
         |}
         """.stripMargin.trim)
  }

  override val testEnv = {
    TestConfig(
      """
        |<?php
        |function main(array $args) : array {
        |    return [
        |       "env" => $_ENV,
        |       "api_host" => $_ENV['__OW_API_HOST'],
        |       "api_key" => $_ENV['__OW_API_KEY'],
        |       "namespace" => $_ENV['__OW_NAMESPACE'],
        |       "action_name" => $_ENV['__OW_ACTION_NAME'],
        |       "activation_id" => $_ENV['__OW_ACTIVATION_ID'],
        |       "deadline" => $_ENV['__OW_DEADLINE'],
        |    ];
        |}
      """.stripMargin.trim,
      enforceEmptyOutputStream = enforceEmptyOutputStream)
  }

  override val testLargeInput = {
    TestConfig("""
        |<?php
        |function main(array $args) : array {
        |    echo 'hello stdout';
        |    error_log('hello stderr');
        |    return $args;
        |}
      """.stripMargin)
  }

  it should "fail to initialize with bad code" in {
    val (out, err) = withPhp7Container { c =>
      val code = """
                |<?php
                | 10 PRINT "Hello world!"
                | 20 GOTO 10
            """.stripMargin

      val (initCode, error) = c.init(initPayload(code))
      initCode should not be (200)
      error shouldBe a[Some[_]]
      error.get shouldBe a[JsObject]
      error.get.fields("error").toString should include("PHP syntax error")
    }

    // Somewhere, the logs should mention an error occurred.
    checkStreams(out, err, {
      case (o, e) =>
        (o + e).toLowerCase should include("error")
        (o + e).toLowerCase should include("syntax")
    })
  }

  it should "return some error on action error" in {
    val (out, err) = withPhp7Container { c =>
      val code = """
                |<?php
                | function main(array $args) : array {
                |     throw new Exception ("nooooo");
                | }
            """.stripMargin

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should not be (200)

      runRes shouldBe defined
      runRes.get.fields.get("error") shouldBe defined
    // runRes.get.fields("error").toString.toLowerCase should include("nooooo")
    }

    // Somewhere, the logs should be the error text
    checkStreams(out, err, {
      case (o, e) =>
        (o + e).toLowerCase should include("nooooo")
    })

  }

  it should "support application errors" in {
    withPhp7Container { c =>
      val code = """
                |<?php
                | function main(array $args) : array {
                |     return [ "error" => "sorry" ];
                | }
            """.stripMargin;

      val (initCode, error) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(200) // action writer returning an error is OK

      runRes shouldBe defined
      runRes.get.fields.get("error") shouldBe defined
      runRes.get.fields("error").toString.toLowerCase should include("sorry")
    }
  }

  it should "fail gracefully when an action has a fatal error" in {
    val (out, err) = withPhp7Container { c =>
      val code = """
                | <?php
                | function main(array $args) : array {
                |     eval("class Error {};");
                |     return [ "hello" => "world" ];
                | }
            """.stripMargin;

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(502)

      runRes shouldBe defined
      runRes.get.fields.get("error") shouldBe defined
      runRes.get.fields("error").toString should include("An error occurred running the action.")
    }

    // Somewhere, the logs should be the error text
    checkStreams(out, err, {
      case (o, e) =>
        (o + e).toLowerCase should include("fatal error")
    })
  }

  it should "suport returning a stdClass" in {
    val (out, err) = withPhp7Container { c =>
      val code = """
                | <?php
                | function main($params) {
                |     $obj = new stdClass();
                |     $obj->hello = 'world';
                |     return $obj;
                | }
            """.stripMargin

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(200) // action writer returning an error is OK

      runRes shouldBe defined
      runRes.get.fields.get("hello") shouldBe defined
      runRes.get.fields("hello").toString.toLowerCase should include("world")
    }
  }

  it should "support returning an object with a getArrayCopy() method" in {
    val (out, err) = withPhp7Container { c =>
      val code = """
                | <?php
                | function main($params) {
                |     $obj = new ArrayObject();
                |     $obj['hello'] = 'world';
                |     return $obj;
                | }
            """.stripMargin

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(200) // action writer returning an error is OK

      runRes shouldBe defined
      runRes.get.fields.get("hello") shouldBe defined
      runRes.get.fields.get("hello") shouldBe Some(JsString("world"))
    }
  }

  it should "support the documentation examples (1)" in {
    val (out, err) = withPhp7Container { c =>
      val code = """
                | <?php
                | function main($params) {
                |     if ($params['payload'] == 0) {
                |         return;
                |     } else if ($params['payload'] == 1) {
                |         return ['payload' => 'Hello, World!'] ;        // indicates normal completion
                |     } else if ($params['payload'] == 2) {
                |         return ['error' => 'payload must be 0 or 1'];  // indicates abnormal completion
                |     }
                | }
            """.stripMargin

      c.init(initPayload(code))._1 should be(200)

      val (c1, r1) = c.run(runPayload(JsObject("payload" -> JsNumber(0))))
      val (c2, r2) = c.run(runPayload(JsObject("payload" -> JsNumber(1))))
      val (c3, r3) = c.run(runPayload(JsObject("payload" -> JsNumber(2))))

      c1 should be(200)
      r1 should be(Some(JsObject()))

      c2 should be(200)
      r2 should be(Some(JsObject("payload" -> JsString("Hello, World!"))))

      c3 should be(200) // application error, not container or system
      r3.get.fields.get("error") shouldBe Some(JsString("payload must be 0 or 1"))
    }
  }

  it should "have Guzzle and Uuid packages available" in {
    // GIVEN that it should "error when requiring a non-existent package" (see test above for this)
    val (out, err) = withPhp7Container { c =>
      val code = """
                | <?php
                | use Ramsey\Uuid\Uuid;
                | use GuzzleHttp\Client;
                | function main(array $args) {
                |     Uuid::uuid4();
                |     new Client();
                | }
            """.stripMargin

      val (initCode, _) = c.init(initPayload(code))

      initCode should be(200)

      // WHEN I run an action that calls a Guzzle & a Uuid method
      val (runCode, out) = c.run(runPayload(JsObject()))

      // THEN it should pass only when these packages are available
      runCode should be(200)
    }
  }

  it should "support large-ish actions" in {
    val thought = " I took the one less traveled by, and that has made all the difference."
    val assignment = "    $x = \"" + thought + "\";\n"

    val code = """
            | <?php
            | function main(array $args) {
            |     $x = "hello";
            """.stripMargin + (assignment * 7000) + """
            |     $x = "world";
            |     return [ "message" => $x ];
            | }
            """.stripMargin

    // Lest someone should make it too easy.
    code.length should be >= 500000

    val (out, err) = withPhp7Container { c =>
      c.init(initPayload(code))._1 should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))

      runCode should be(200)
      runRes.get.fields.get("message") shouldBe defined
      runRes.get.fields.get("message") shouldBe Some(JsString("world"))
    }
  }

  val exampleOutputDotPhp: String = """
        | <?php
        | function output($data) {
        |     return ['result' => $data];
        | }
    """.stripMargin

  it should "support zip-encoded packages" in {
    val srcs = Seq(
      Seq("output.php") -> exampleOutputDotPhp,
      Seq("index.php") -> """
                | <?php
                | require __DIR__ . '/output.php';
                | function main(array $args) {
                |     $name = $args['name'] ?? 'stranger';
                |     return output($name);
                | }
            """.stripMargin)

    val code = ZipBuilder.mkBase64Zip(srcs)

    val (out, err) = withPhp7Container { c =>
      c.init(initPayload(code))._1 should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))

      runCode should be(200)
      runRes.get.fields.get("result") shouldBe defined
      runRes.get.fields.get("result") shouldBe Some(JsString("stranger"))
    }
  }

  it should "support replacing vendor in zip-encoded packages " in {
    val srcs = Seq(
      Seq("vendor/autoload.php") -> exampleOutputDotPhp,
      Seq("index.php") -> """
                | <?php
                | function main(array $args) {
                |     $name = $args['name'] ?? 'stranger';
                |     return output($name);
                | }
            """.stripMargin)

    val code = ZipBuilder.mkBase64Zip(srcs)

    val (out, err) = withPhp7Container { c =>
      c.init(initPayload(code))._1 should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))

      runCode should be(200)
      runRes.get.fields.get("result") shouldBe defined
      runRes.get.fields.get("result") shouldBe Some(JsString("stranger"))
    }
  }

  it should "fail gracefully on invalid zip files" in {
    // Some text-file encoded to base64.
    val code = "Q2VjaSBuJ2VzdCBwYXMgdW4gemlwLgo="

    val (out, err) = withPhp7Container { c =>
      val (initCode, error) = c.init(initPayload(code))
      initCode should not be (200)
      error shouldBe a[Some[_]]
      error.get shouldBe a[JsObject]
      error.get.fields("error").toString should include("Failed to open zip file")
    }

    // Somewhere, the logs should mention the failure
    checkStreams(out, err, {
      case (o, e) =>
        (o + e).toLowerCase should include("error")
        (o + e).toLowerCase should include("failed to open zip file")
    })
  }

  it should "fail gracefully on valid zip files that are not actions" in {
    val srcs = Seq(Seq("hello") -> """
                | Hello world!
            """.stripMargin)

    val code = ZipBuilder.mkBase64Zip(srcs)

    val (out, err) = withPhp7Container { c =>
      c.init(initPayload(code))._1 should not be (200)
    }

    checkStreams(out, err, {
      case (o, e) =>
        (o + e).toLowerCase should include("error")
        (o + e).toLowerCase should include("zipped actions must contain index.php at the root.")
    })
  }

  it should "fail gracefully on valid zip files with invalid code in index.php" in {
    val (out, err) = withPhp7Container { c =>
      val srcs = Seq(Seq("index.php") -> """
                    | <?php
                    | 10 PRINT "Hello world!"
                    | 20 GOTO 10
                """.stripMargin)

      val code = ZipBuilder.mkBase64Zip(srcs)

      val (initCode, error) = c.init(initPayload(code))
      initCode should not be (200)
      error shouldBe a[Some[_]]
      error.get shouldBe a[JsObject]
      error.get.fields("error").toString should include("PHP syntax error in index.php")
    }

    // Somewhere, the logs should mention an error occurred.
    checkStreams(out, err, {
      case (o, e) =>
        (o + e).toLowerCase should include("error")
        (o + e).toLowerCase should include("syntax")
    })
  }

  it should "support zipped actions using non-default entry point" in {
    val srcs = Seq(Seq("index.php") -> """
                | <?php
                | function niam(array $args) {
                |     return [result => "it works"];
                | }
            """.stripMargin)

    val code = ZipBuilder.mkBase64Zip(srcs)

    withPhp7Container { c =>
      c.init(initPayload(code, main = "niam"))._1 should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runRes.get.fields.get("result") shouldBe Some(JsString("it works"))
    }
  }
}
