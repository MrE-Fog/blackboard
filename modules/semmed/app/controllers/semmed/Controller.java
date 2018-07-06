package controllers.semmed;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.CompletionStage;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Provider;

import play.Configuration;
import play.mvc.*;
import play.libs.ws.WSResponse;
import play.Logger;
import play.Environment;
import play.libs.Json;
import play.libs.concurrent.HttpExecutionContext;
import play.routing.JavaScriptReverseRouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import blackboard.semmed.SemMedDbKSource;
import blackboard.semmed.Predication;
import blackboard.semmed.PredicateSummary;
import blackboard.semmed.SemanticType;
import blackboard.umls.MatchedConcept;

@Singleton
public class Controller extends play.mvc.Controller {
    final SemMedDbKSource ks;
    final HttpExecutionContext ec;
    final Environment env;
    
    @Inject
    public Controller (HttpExecutionContext ec,
                       Environment env, SemMedDbKSource ks) {
        this.ks = ks;
        this.ec = ec;
        this.env = env;
    }

    public Result index () {
        return ok (views.html.semmed.index.render(ks));
    }

    public Result apiSemanticTypes () {
        return ok (Json.prettyPrint(Json.toJson(ks.semanticTypes)))
            .as("application/json");
    }

    public Result apiSemanticTypeLookup (String str) {
        for (SemanticType st : ks.semanticTypes) {
            if (st.abbr.equalsIgnoreCase(str) || st.id.equalsIgnoreCase(str))
                return ok (Json.toJson(st));
        }
        return notFound ("Can't lookup semantic type "
                         +"either by id or abbr: "+str);
    }

    public CompletionStage<Result> apiSearch
        (final String q, final Integer skip, final Integer top) {
        return supplyAsync (() -> {
                try {
                    List<MatchedConcept> concepts =
                        ks.umls.findConcepts(q, skip, top);
                    ArrayNode result = Json.newArray();
                    for (MatchedConcept mc : concepts) {
                        PredicateSummary ps = ks.getPredicateSummary(mc.cui);

                        ObjectNode concept = Json.newObject();
                        if (mc.score != null)
                            concept.put("score", mc.score);
                        concept.put("cui", mc.cui);
                        concept.put("name", mc.name);
                        concept.put("source", mc.concept.source.name);
                        concept.put("semtypes",
                                    Json.toJson(mc.concept.semanticTypes
                                                .stream().map(t -> t.name)
                                                .toArray(String[]::new)));
                        ObjectNode obj = (ObjectNode)Json.toJson(ps);
                        obj.put("concept", concept);
                        result.add(obj);
                    }
                    return ok (result);
                }
                catch (Exception ex) {
                    Logger.error("Search failed", ex);
                    return internalServerError (ex.getMessage());
                }
            }, ec.current());
    }

    public CompletionStage<Result> apiPredicateSummary (final String cui) {
        return supplyAsync (() -> {
                try {
                    PredicateSummary ps = ks.getPredicateSummary(cui);
                    return ok (Json.prettyPrint(Json.toJson(ps)))
                        .as("application/json");
                }
                catch (Exception ex) {
                    Logger.error("PredicateSummary failed", ex);
                    return internalServerError (ex.getMessage());
                }                    
            }, ec.current());
    }

    Result filter (final String cui, Predicate<Predication> pred) {
        try {
            Predication[] preds = ks
                .getPredications(cui).stream()
                .filter(pred)
                .toArray(Predication[]::new);
            
            return ok (Json.prettyPrint(Json.toJson(preds)))
                .as("application/json");
        }
        catch (Exception ex) {
            Logger.error("Predicate failed", ex);
            return internalServerError (ex.getMessage());
        }
    }
  
    public CompletionStage<Result> apiPredicate (final String cui,
                                              final String predicate) {
        return supplyAsync (() -> {
                return filter (cui, p -> p.predicate.equals(predicate));
            }, ec.current());
    }

    public CompletionStage<Result> apiSemtype (final String cui,
                                               final String semtype) {
        return supplyAsync (() -> {
                return filter (cui, p -> semtype.equals(p.subtype)
                               || semtype.equals(p.objtype));
            }, ec.current());
    }

    public Result predicate (final String cui, final String predicate) {
        return ok (views.html.semmed.predicate.render(ks, cui, predicate));
    }

    public Result jsRoutes () {
        return ok (JavaScriptReverseRouter.create
                   ("semmedRoutes",
                    routes.javascript.Controller.predicate(),
                    routes.javascript.Controller.apiSemanticTypeLookup()
                    )).as("text/javascript");
    }
}
