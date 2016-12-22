package ru.reajames

import Jms._
import org.scalatest._
import javax.jms.TextMessage
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.activemq.ActiveMQConnectionFactory

/**
  * Specification on JmsPublisher.
  * @author Dmitry Dobrynin <dobrynya@inbox.ru>
  *         Created at 21.12.16 1:41.
  */
class JmsPublisherSpec extends FlatSpec with Matchers with JmsUtilities {
  private implicit val connectionFactory = new ActiveMQConnectionFactory("vm://test-broker?broker.persistent=false&broker.useJmx=false")
  private implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "JmsPublisher" should "raise an exception in case whether subscriber is not specified" in {
    val queue = Queue("queue-5")
    val pub = new JmsPublisher(connectionFactory, queue)
    intercept[NullPointerException](pub.subscribe(null))
  }

  it should "publish messages arrived to a JMS queue" in {
    val queue = Queue("queue-6")
    val pub = new JmsPublisher(connectionFactory, queue)

    var received = List.empty[String]
    pub.subscribe(TestSubscriber(
      request = Some(Long.MaxValue),
      next = { (s, msg) =>
        received ::= msg.asInstanceOf[TextMessage].getText
        if (received.head == "500") s.cancel()
      }
    ))

    val expected = (1 to 500).map(_.toString).toList
    sendMessages(expected, string2textMessage, queue)
    received.reverse should equal(expected)
  }

  it should "not call subscriber concurrently" in {
    val queue = Queue("queue-7")
    val pub = new JmsPublisher(connectionFactory, queue)

    var checked = true
    val called = new AtomicBoolean(false)

    def checkCalled(): Unit = {
      checked = called.compareAndSet(false, true)
      Thread.sleep(100)
      checked = called.compareAndSet(true, false)
    }

    pub.subscribe(TestSubscriber(
      request = Some(1000),
      subscribe = _ => checkCalled(),
      error = _ => checkCalled(),
      complete = () => checkCalled(),
      next = (s, msg) => if (msg.asInstanceOf[TextMessage].getText == "1000") s.cancel()
    ))

    sendMessages((1 to 1000).map(_.toString), string2textMessage, queue)
    checked should equal(true)
  }

  it should "receive only requested amount of messages" in {
    val topic = Topic("topic-8")
    val pub = new JmsPublisher(connectionFactory, topic)

    @volatile var counter = 0
    pub.subscribe(TestSubscriber(request = Some(100), next = (s, msg) => counter += 1))

    sendMessages((1 to 200).map(_.toString), string2textMessage, topic)

    counter should equal(100)
  }

  it should "publish messages from a topic to different subscribers" in {
    var res1, res2 = List.empty[String]
    val topic = Topic("topic-10")
    val pub = new JmsPublisher(connectionFactory, topic)
    pub.subscribe(TestSubscriber(
      request = Some(100),
      next = (s, msg) => res1 ::= msg.asInstanceOf[TextMessage].getText
    ))
    pub.subscribe(TestSubscriber(
      request = Some(100),
      next = (s, msg) => res2 ::= msg.asInstanceOf[TextMessage].getText
    ))

    val msgs = (1 to 100).map(_.toString)
    sendMessages(msgs, string2textMessage, topic)
    res1 should equal(msgs.reverse)
    res2 should equal(msgs.reverse)
  }
}