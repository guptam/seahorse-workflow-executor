/**
 * Copyright 2015, CodiLime Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.deepsense.workflowexecutor

import scala.concurrent.{Promise, Future}

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.testkit.{TestKit, TestActorRef, TestProbe}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScaledTimeSpans}
import org.scalatest.mock.MockitoSugar

import io.deepsense.commons.{StandardSpec, UnitTestSupport}
import io.deepsense.deeplang.catalogs.doperable.DOperableCatalog
import io.deepsense.deeplang.{DOperable, DOperation, ExecutionContext}
import io.deepsense.graph.{GraphState, Graph, Node}
import io.deepsense.workflowexecutor.WorkflowExecutorActor.Messages.{Launch, GraphFinished}
import io.deepsense.workflowexecutor.WorkflowExecutorActor._
import io.deepsense.models.entities.Entity
import io.deepsense.models.workflows.{ThirdPartyData, WorkflowMetadata, Workflow}

class WorkflowExecutorActorSpec
  extends StandardSpec
  with UnitTestSupport
  with WordSpecLike
  with BeforeAndAfterAll
  with Matchers
  with MockitoSugar
  with Eventually
  with ScaledTimeSpans {

  trait TestCase {
    implicit val system = ActorSystem("WorkflowExecutorActorSpec")
    val executionContext = new ExecutionContext(mock[DOperableCatalog])
    val graph = {
      val g = mock[Graph]
      when(g.enqueueNodes) thenReturn g
      when(g.markRunning) thenReturn g
      g
    }

    class Wrapper(target: ActorRef) extends Actor {
      def receive: Receive = {
        case x => target forward x
      }
    }

    trait TestGraphNodeExecutorFactory extends GraphNodeExecutorFactory {
      var nodeExecutors: Seq[TestProbe] = _
      var expectedNodes: List[Node] = _
      var nodesToVisit: List[Node] = _
      var expectedDOperableCache: Results = _
      var expectedGraph = graph

      def createGraphNodeExecutor(
          ec: ExecutionContext,
          node: Node,
          graph: Graph,
          dOperableCache: Results): Actor = {
        synchronized {
          ec shouldBe executionContext
          graph shouldBe expectedGraph
          nodesToVisit should contain(node)
          dOperableCache shouldBe expectedDOperableCache
          val indexOfNode = expectedNodes.indexOf(node)
          val returnedExecutor = nodeExecutors(indexOfNode)
          nodesToVisit = nodesToVisit.filter(n => n != node)
          new Wrapper(returnedExecutor.ref)
        }
      }
    }

    val shutdownerProbe = TestProbe()

    trait TestShutdowner extends SystemShutdowner {
      override def shutdownSystem: Unit = {
        shutdownerProbe.ref ! shutdownMessage
      }
    }

    val weaRef = TestActorRef[
      WorkflowExecutorActor
        with TestGraphNodeExecutorFactory
        with TestShutdowner
      ](Props(new WorkflowExecutorActor(executionContext)
        with TestGraphNodeExecutorFactory
        with TestShutdowner))

    val wea = weaRef.underlyingActor

    def verifySystemShutDown(): Unit = {
      shutdownerProbe.expectMsgType[String] shouldBe shutdownMessage
    }

    def launchGraph(): Future[GraphFinished] = {
      val nodes = List(mockNode(), mockNode())
      val nodeExecutors = Seq(TestProbe(), TestProbe())
      val running = graph.markRunning
      when(running.readyNodes).thenReturn(nodes)
      wea.expectedNodes = nodes
      wea.nodesToVisit = nodes
      wea.nodeExecutors = nodeExecutors
      wea.expectedDOperableCache = Map.empty

      val resultPromise: Promise[GraphFinished] = Promise()
      weaRef ! Launch(graph, false, resultPromise)
      wea.nodeExecutors(0).expectMsg(WorkflowNodeExecutorActor.Messages.Start())
      wea.nodeExecutors(1).expectMsg(WorkflowNodeExecutorActor.Messages.Start())
      wea.nodesToVisit shouldBe empty
      resultPromise.future
    }

    def launchEmptyWorkflow(): Unit = {
      val nodes = List()
      val nodeExecutors = Seq()
      when(graph.readyNodes).thenReturn(nodes)
      when(graph.nodes).thenReturn(Set.empty[Node])
      wea.expectedNodes = nodes
      wea.nodeExecutors = nodeExecutors
      wea.expectedDOperableCache = Map.empty

      weaRef ! graph
    }

    def mockOperation(): DOperation = {
      val operation = mock[DOperation]
      when(operation.inArity) thenReturn 0
      when(operation.outArity) thenReturn 1
      when(operation.id) thenReturn DOperation.Id.randomId
      operation
    }

    def mockNode(): Node = {
      val operation = mockOperation()
      val node = mock[Node]
      when(node.id) thenReturn Node.Id.randomId
      when(node.operation) thenReturn operation
      node
    }

    def mockFinishedNode(): Node = {
      val finishedNode = mockNode()
      when(finishedNode.isRunning) thenReturn false
      when(finishedNode.isCompleted) thenReturn true
      when(finishedNode.isFailed) thenReturn false
      finishedNode
    }
  }

  val shutdownMessage = "Shutdown"

  "WorkflowExecutorActor" should {
    "start GraphNodeExecutorActor for each ready node and send Start to it" when {
      "it receives Launch" in new TestCase {
        launchGraph()
      }
    }
    "close actor system and returns a result" when {
      "it receives NodeFinished and there are no nodes left for execution" in new TestCase {
        val result = launchGraph()
        val finishedNode = mockFinishedNode()
        val finishedGraph = mock[Graph]
        val runningGraph = mock[Graph]
        when(runningGraph.withChangedNode(any())) thenReturn runningGraph
        when(runningGraph.updateState()) thenReturn finishedGraph
        when(runningGraph.readyNodes) thenReturn List.empty
        when(runningGraph.runningNodes) thenReturn Set.empty[Node]
        when(finishedGraph.state) thenReturn mock[GraphState]

        wea.graph = runningGraph
        weaRef ! WorkflowExecutorActor.Messages.NodeFinished(finishedNode, Map.empty)
        verifySystemShutDown()
        result shouldBe 'completed
      }
    }
  }
}