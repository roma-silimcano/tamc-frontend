/*
 * Copyright 2016 HM Revenue & Customs
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

package actions

import scala.Right
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import config.ApplicationConfig
import connectors.ApplicationAuditConnector
import play.api.Logger
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import play.api.mvc.Results.Redirect
import play.api.mvc.Session
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.config.RunMode
import uk.gov.hmrc.play.frontend.auth.Actions
import uk.gov.hmrc.play.frontend.auth.AuthContext
import uk.gov.hmrc.play.frontend.auth.AuthenticationProvider
import uk.gov.hmrc.play.frontend.auth.UpliftingIdentityConfidencePredicate
import uk.gov.hmrc.play.frontend.auth.connectors.domain.ConfidenceLevel
import uk.gov.hmrc.play.frontend.auth.TaxRegime
import uk.gov.hmrc.play.frontend.auth.UserCredentials
import uk.gov.hmrc.play.frontend.auth.connectors.domain.Accounts
import uk.gov.hmrc.play.http.SessionKeys
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import events.RiskTriageRedirectEvent
import utils.TamcBreadcrumb
import java.net.URI
import uk.gov.hmrc.play.frontend.controller.FrontendController
import details.TamcUser
import details.PersonDetails
import details.CitizenDetailsService
import details.PersonDetailsNotFoundResponse
import config.ApplicationGlobal
import details.PersonDetailsErrorResponse
import details.PersonDetailsSuccessResponse
import uk.gov.hmrc.play.frontend.auth.Verify
import details.Person

object IdaAuthentificationProvider extends IdaAuthentificationProvider {
  override val login = ApplicationConfig.ivLoginUrl
  override val customAuditConnector = ApplicationAuditConnector
}

trait IdaAuthentificationProvider extends Verify with RunMode with TamcBreadcrumb with JourneyEnforcers {

  val customAuditConnector: AuditConnector

  override def redirectToLogin(implicit request: Request[_]): Future[Result] =
 {
        customAuditConnector.sendEvent(RiskTriageRedirectEvent())
        super.redirectToLogin
    }

  override def handleSessionTimeout(implicit request: Request[_]): Future[Result] =
    Future {
      if (isGdsJourney(request)) {
        BadRequest(views.html.errors.session_timedout()).withNewSession
      } else {
        BadRequest(views.html.pta.session_timedout_pta()).withNewSession
      }
    }
}

object MarriageAllowanceRegime extends MarriageAllowanceRegime {
  override val authenticationType = IdaAuthentificationProvider
}

trait MarriageAllowanceRegime extends TaxRegime {
  override def isAuthorised(accounts: Accounts) = accounts.paye.isDefined
  override val unauthorisedLandingPage = Some(controllers.routes.AuthorisationController.notAuthorised().url)
}

trait AuthorisedActions extends Actions {

  this: FrontendController =>

  val ivUpliftUrl: String

  val maAuthRegime: MarriageAllowanceRegime

  def citizenDetailsService: CitizenDetailsService

  def withPersonDetails(block: PersonDetails => Future[Result], tamcUser: TamcUser)(implicit hc: HeaderCarrier, request: Request[_], authContext: AuthContext): Future[Result] = {
    citizenDetailsService.getPersonDetails(tamcUser.nino) flatMap { personDetails =>
      personDetails match {
        case PersonDetailsSuccessResponse(pd) =>
          block(pd)
        case _ =>
          withEmptyPerson(block)
      }
    }
  }

  def withEmptyPerson(block: PersonDetails => Future[Result])(implicit hc: HeaderCarrier, request: Request[_], authContext: AuthContext): Future[Result] = {
    block(PersonDetails(Person(None)))
  }

  private object AuthorisedForMarriageAllowance {
    def apply(block: AuthContext => Request[_] => Future[Result]): Action[AnyContent] = {
      AuthorisedFor(taxRegime = maAuthRegime, pageVisibility = new UpliftingIdentityConfidencePredicate(ConfidenceLevel.L100, new URI(ivUpliftUrl))).async {
        block
      }
    }
  }

  object TamcAuthPersonalDetailsAction extends JourneyEnforcers {
    def apply(block: AuthContext => Request[_] => PersonDetails => Future[Result]): Action[AnyContent] = {
      AuthorisedForMarriageAllowance {
        implicit authContext =>
          implicit request =>
            isGdsJourney match {
              case true  => withEmptyPerson(block(authContext)(request))
              case false => withPersonDetails(block(authContext)(request), TamcUser(authContext))
            }
      }
    }
  }
}
