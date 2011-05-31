/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray
package directives

import marshalling._
import cc.spray.RequestEntityExpectedRejection

private[spray] trait MarshallingDirectives extends DefaultMarshallers with DefaultUnmarshallers {
  this: BasicDirectives =>

  /**
   * Returns a Route that unmarshalls the requests content using the in-scope unmarshaller for the given type and
   * passes it as an argument to the inner Route building function.
   * If the unmarshaller cannot unmarshal the request content the request is rejected with the [[cc.spray.Rejection]]
   * produced by the unmarshaller.
   */
  def contentAs[A :Unmarshaller](routing: A => Route): Route = {
    val filterRoute = filter1 { ctx =>
      ctx.request.content.as[A] match {
        case Right(a) => Pass.withTransform(a)(_.cancelRejections[UnsupportedRequestContentTypeRejection])
        case Left(rejection) => Reject(rejection)
      }
    }
    filterRoute(routing) 
  }

  /**
   * Returns a Route that unmarshalls the optional request content using the in-scope unmarshaller for the given type
   * and passes it as an argument to the inner Route building function.
   * If the unmarshaller cannot unmarshal the request content the request is rejected with the [[cc.spray.Rejection]]
   * produced by the unmarshaller.
   */
  def optionalContentAs[A :Unmarshaller](routing: Option[A] => Route): Route = {
    val filterRoute = filter1 { ctx =>
      ctx.request.content.as[A] match {
        case Right(a) => Pass.withTransform(Some(a)) {
          _.cancelRejections { r =>
            r.isInstanceOf[UnsupportedRequestContentTypeRejection] || r == RequestEntityExpectedRejection
          }
        }
        case Left(RequestEntityExpectedRejection) => Pass(None)
        case Left(rejection) => Reject(rejection)
      }
    }
    filterRoute(routing) 
  }

  /**
   * Returns a Route that uses the in-scope marshaller for the given type to produce a completion function that is
   * passed to the inner route building function. You can use it do decouple marshaller resolution from the call
   * site of the RequestContexts 'complete' function.
   */
  def produce[A](routing: (A => Unit) => Route)(implicit marshaller: Marshaller[A]): Route = {
    val filterRoute = filter1 { ctx =>
      marshaller(ctx.request.acceptableContentType) match {
        case MarshalWith(converter) => Pass.withTransform((a: A) => ctx.complete(converter(a))) {
          _.cancelRejections[UnacceptedResponseContentTypeRejection]
        }
        case CantMarshal(onlyTo) => Reject(UnacceptedResponseContentTypeRejection(onlyTo))
      }
    }
    filterRoute(routing)
  }

  /**
   * Returns a Route that completes the request using the given function. The input to the function is produce with
   * the in-scope unmarshaller and the result value of the function is marshalled with the in-scope marshaller.
   */
  def handledBy[A :Unmarshaller, B: Marshaller](f: A => B): Route = {
    contentAs[A] { a =>
      produce[B] { produce =>
        _ => produce(f(a))
      }
    }
  }
  
}