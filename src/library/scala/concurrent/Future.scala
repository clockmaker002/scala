
/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package scala.concurrent

//import akka.AkkaException (replaced with Exception)
//import akka.event.Logging.Error (removed all logging)
import scala.util.{ Timeout, Duration }
import scala.Option
//import akka.japi.{ Procedure, Function ⇒ JFunc, Option ⇒ JOption } (commented methods)

import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit, Callable }
import java.util.concurrent.TimeUnit.{ NANOSECONDS ⇒ NANOS, MILLISECONDS ⇒ MILLIS }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }

import scala.annotation.tailrec
import scala.collection.mutable.Stack
//import akka.util.Switch (commented method)
import java.{ lang ⇒ jl }
import java.util.concurrent.atomic.{ AtomicReferenceFieldUpdater, AtomicInteger, AtomicBoolean }



/** The trait that represents futures.
 *  
 *  @define futureTimeout
 *  The timeout of the future is:
 *  - if this future was obtained from a task (i.e. by calling `task.future`), the timeout associated with that task
 *  - if this future was obtained from a promise (i.e. by calling `promise.future`), the timeout associated with that promise
 *
 *  @define multipleCallbacks
 *  Multiple callbacks may be registered; there is no guarantee that they will be
 *  executed in a particular order.
 *
 *  @define caughtThrowables
 *  The future may contain a throwable object and this means that the future failed.
 *  Futures obtained through combinators have the same exception as the future they were obtained from.
 *  The following throwable objects are not contained in the future:
 *  - Error - errors are not contained within futures
 *  - scala.util.control.ControlThrowable - not contained within futures
 *  - InterruptedException - not contained within futures
 *  
 *  Instead, the future is completed with a ExecutionException with one of the exceptions above
 *  as the cause.
 *
 *  @define forComprehensionExamples
 *  Example:
 *  
 *  {{{
 *  val f = future { 5 }
 *  val g = future { 3 }
 *  val h = for {
 *    x: Int <- f // returns Future(5)
 *    y: Int <- g // returns Future(5)
 *  } yield x + y
 *  }}}
 *  
 *  is translated to:
 *  
 *  {{{
 *  f flatMap { (x: Int) => g map { (y: Int) => x + y } }
 *  }}}
 */
trait Future[+T] extends Blockable[T] {
self =>
  
  /* Callbacks */
  
  /** When this future is completed successfully (i.e. with a value),
   *  apply the provided function to the value.
   *  
   *  If the future has already been completed with a value,
   *  this will either be applied immediately or be scheduled asynchronously.
   *  
   *  Will not be called in case of an exception (this includes the FutureTimeoutException).
   *  
   *  $multipleCallbacks
   */
  def onSuccess[U](func: T => U): this.type = onComplete {
    case Left(t) => // do nothing
    case Right(v) => func(v)
  }
  
  /** When this future is completed with a failure (i.e. with a throwable),
   *  apply the provided callback to the throwable.
   *  
   *  $caughtThrowables
   *  
   *  If the future has already been completed with a failure,
   *  this will either be applied immediately or be scheduled asynchronously.
   *  
   *  Will not be called in case that the future is completed with a value.
   *  
   *  Will be called if the future is completed with a FutureTimeoutException.
   *  
   *  $multipleCallbacks
   */
  def onFailure[U](callback: PartialFunction[Throwable, U]): this.type = onComplete {
    case Left(t) if t.isInstanceOf[FutureTimeoutException] || isFutureThrowable(t) => if (callback.isDefinedAt(t)) callback(t)
    case Right(v) => // do nothing
  }
  
  /** When this future times out, apply the provided function.
   *  
   *  If the future has already timed out,
   *  this will either be applied immediately or be scheduled asynchronously.
   *  
   *  $multipleCallbacks
   */
  def onTimeout[U](callback: FutureTimeoutException => U): this.type = onComplete {
    case Left(te: FutureTimeoutException) => callback(te)
    case Right(v) => // do nothing
  }
  
  /** When this future is completed, either through an exception, a timeout, or a value,
   *  apply the provided function.
   *  
   *  If the future has already been completed,
   *  this will either be applied immediately or be scheduled asynchronously.
   *  
   *  $multipleCallbacks
   */
  def onComplete[U](func: Either[Throwable, T] => U): this.type
  
  
  /* Miscellaneous */
  
  /** The execution context of the future.
   */
  def executionContext: ExecutionContext
  
  /** Creates a new promise.
   */
  def newPromise[S]: Promise[S] = executionContext promise
  
  /** Tests whether this Future's timeout has expired.
   *
   *  $futureTimeout
   *  
   *  Note that an expired Future may still contain a value, or it may be
   *  completed with a value.
   */
  def isTimedout: Boolean
  
  /** This future's timeout.
   *  
   *  $futureTimeout
   */
  def timeout: Timeout
  
  /** This future's timeout in nanoseconds.
   *  
   *  $futureTimeout
   */
  def timeoutInNanos = if (timeout.duration.isFinite) timeout.duration.toNanos else Long.MaxValue
  
  
  /* Projections */
  
  /** A failed projection of the future.
   *  
   */
  def failed: Future[Throwable] = new Future[Throwable] {
    def executionContext = self.executionContext
    def onComplete[U](func: Either[Throwable, Throwable] => U) = {
      self.onComplete {
        case Left(t) => func(Right(t))
        case Right(v) => func(Left(noSuchElem(v))) // do nothing
      }
      this
    }
    def isTimedout = self.isTimedout
    def timeout = self.timeout
    def block()(implicit canblock: CanBlock) = try {
      val res = self.block()
      throw noSuchElem(res)
    } catch {
      case t: Throwable => t
    }
    private def noSuchElem(v: T) = 
      new NoSuchElementException("Future.failed not completed with a throwable. Instead completed with: " + v)
  }
  
  /** A timed out projection of the future.
   */
  def timedout: Future[FutureTimeoutException] = new Future[FutureTimeoutException] {
    def executionContext = self.executionContext
    def onComplete[U](func: Either[Throwable, FutureTimeoutException] => U) = {
      self.onComplete {
        case Left(te: FutureTimeoutException) => func(Right(te))
        case Left(t) => func(Left(noSuchElemThrowable(t)))
        case Right(v) => func(Left(noSuchElemValue(v)))
      }
      this
    }
    def isTimedout = self.isTimedout
    def timeout = self.timeout
    def block()(implicit canblock: CanBlock) = try {
      val res = self.block() // TODO fix
      throw noSuchElemValue(res)
    } catch {
      case ft: FutureTimeoutException =>
        ft
      case t: Throwable =>
        throw noSuchElemThrowable(t)
    }
    private def noSuchElemValue(v: T) =
      new NoSuchElementException("Future.timedout didn't time out. Instead completed with: " + v)
    private def noSuchElemThrowable(v: Throwable) =
      new NoSuchElementException("Future.timedout didn't time out. Instead failed with: " + v)
  }
  
  
  /* Monadic operations */
  
  /** Creates a new future that will handle any matching throwable that this
   *  future might contain. If there is no match, or if this future contains
   *  a valid result then the new future will contain the same.
   *  
   *  Example:
   *  
   *  {{{
   *  future (6 / 0) recover { case e: ArithmeticException ⇒ 0 } // result: 0
   *  future (6 / 0) recover { case e: NotFoundException   ⇒ 0 } // result: exception
   *  future (6 / 2) recover { case e: ArithmeticException ⇒ 0 } // result: 3
   *  }}}
   */
  def recover[U >: T](pf: PartialFunction[Throwable, U]): Future[U] = {
    val p = newPromise[U]
    
    onComplete {
      case Left(t) => if (pf isDefinedAt t) p fulfill pf(t) else p fail t
      case Right(v) => p fulfill v
    }
    
    p.future
  }
  
  /** Asynchronously processes the value in the future once the value becomes available.
   *  
   *  Will not be called if the future times out or fails.
   *  
   *  This method typically registers an `onSuccess` callback.
   */
  def foreach[U](f: T => U): Unit = onSuccess(f)
  
  /** Creates a new future by applying a function to the successful result of
   *  this future. If this future is completed with an exception then the new
   *  future will also contain this exception.
   *  
   *  $forComprehensionExample
   */
  def map[S](f: T => S): Future[S] = {
    val p = newPromise[S]
    
    onComplete {
      case Left(t) => p fail t
      case Right(v) => p fulfill f(v)
    }
    
    p.future
  }
  
  /** Creates a new future by applying a function to the successful result of
   *  this future, and returns the result of the function as the new future.
   *  If this future is completed with an exception then the new future will
   *  also contain this exception.
   *  
   *  $forComprehensionExample
   */
  def flatMap[S](f: T => Future[S]): Future[S] = {
    val p = newPromise[S]
    
    onComplete {
      case Left(t) => p fail t
      case Right(v) => f(v) onComplete {
        case Left(t) => p fail t
        case Right(v) => p fulfill v
      }
    }
    
    p.future
  }
  
  /** Creates a new future by filtering the value of the current future with a predicate.
   *  
   *  If the current future contains a value which satisfies the predicate, the new future will also hold that value.
   *  Otherwise, the resulting future will fail with a `NoSuchElementException`.
   *  
   *  If the current future fails or times out, the resulting future also fails or times out, respectively.
   *
   *  Example:
   *  {{{
   *  val f = future { 5 }
   *  val g = g filter { _ % 2 == 1 }
   *  val h = f filter { _ % 2 == 0 }
   *  block on g // evaluates to 5
   *  block on h // throw a NoSuchElementException
   *  }}}
   */
  def filter(pred: T => Boolean): Future[T] = {
    val p = newPromise[T]
    
    onComplete {
      case Left(t) => p fail t
      case Right(v) => if (pred(v)) p fulfill v else p fail new NoSuchElementException("Future.filter predicate is not satisfied by: " + v)
    }
    
    p.future
  }
  
}


