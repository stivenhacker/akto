package com.akto.action.observe;

import com.akto.dao.ApiCollectionsDao;
import com.akto.dao.SingleTypeInfoDao;
import com.akto.dao.context.Context;
import com.akto.dto.ApiCollection;
import com.akto.dto.type.SingleTypeInfo;
import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.*;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Utils {

    public final static int DELTA_PERIOD_VALUE = 60 * 24 * 60 * 60;
    public static final int LIMIT = 2000;
    public static final int LARGE_LIMIT = 10_000;

    public static List<BasicDBObject> fetchEndpointsInCollectionUsingHost(int apiCollectionId, int skip) {

        ApiCollection apiCollection = ApiCollectionsDao.instance.getMeta(apiCollectionId);

        if(apiCollection == null){
            return new ArrayList<>();
        }

        if (apiCollection.getHostName() == null || apiCollection.getHostName().length() == 0 ) {
            return fetchEndpointsInCollection(apiCollectionId, skip);
        } else {
            List<SingleTypeInfo> allUrlsInCollection = fetchHostSTI(apiCollectionId, skip);

            List<BasicDBObject> endpoints = new ArrayList<>();
            for(SingleTypeInfo singleTypeInfo: allUrlsInCollection) {
                BasicDBObject groupId = new BasicDBObject("apiCollectionId", singleTypeInfo.getApiCollectionId())
                        .append("url", singleTypeInfo.getUrl())
                        .append("method", singleTypeInfo.getMethod());
                endpoints.add(new BasicDBObject("startTs", singleTypeInfo.getTimestamp()).append("_id", groupId));
            }

            return endpoints;
        }
    }

    public final static String _START_TS = "startTs";
    public final static String _LAST_SEEN_TS = "lastSeenTs";

    public static int countEndpoints(Bson filters) {
        int ret = 0;

        List<Bson> pipeline = new ArrayList<>();
        BasicDBObject groupedId = new BasicDBObject("apiCollectionId", "$apiCollectionId")
                .append("url", "$url")
                .append("method", "$method");

        Bson projections = Projections.fields(
                Projections.include("timestamp", "lastSeen", "apiCollectionId", "url", "method", SingleTypeInfo._COLLECTION_IDS));

        pipeline.add(Aggregates.project(projections));
        pipeline.add(Aggregates.match(filters));
        pipeline.add(Aggregates.group(groupedId));
        pipeline.add(Aggregates.limit(LARGE_LIMIT));
        pipeline.add(Aggregates.count());

        MongoCursor<BasicDBObject> endpointsCursor = SingleTypeInfoDao.instance.getMCollection()
                .aggregate(pipeline, BasicDBObject.class).cursor();

        while (endpointsCursor.hasNext()) {
            ret = endpointsCursor.next().getInt("count");
            break;
        }

        return ret;
    }

    public static List<BasicDBObject> fetchEndpointsInCollection(int apiCollectionId, int skip) {
        List<Bson> pipeline = new ArrayList<>();
        BasicDBObject groupedId =
                new BasicDBObject("apiCollectionId", "$apiCollectionId")
                        .append("url", "$url")
                        .append("method", "$method");

        pipeline.add(Aggregates.match(Filters.in(SingleTypeInfo._COLLECTION_IDS, apiCollectionId)));

        int recentEpoch = Context.now() - DELTA_PERIOD_VALUE;

        Bson projections = Projections.fields(
                Projections.include("timestamp", "apiCollectionId", "url", "method"),
                Projections.computed("dayOfYearFloat", new BasicDBObject("$divide", new Object[]{"$timestamp", recentEpoch})),
                Projections.computed("dayOfYear", new BasicDBObject("$trunc", new Object[]{"$dayOfYearFloat", 0}))
        );

        pipeline.add(Aggregates.project(projections));
        pipeline.add(Aggregates.group(groupedId, Accumulators.min("startTs", "$timestamp"), Accumulators.sum("changesCount", 1)));
        pipeline.add(Aggregates.skip(skip));
        pipeline.add(Aggregates.limit(LIMIT));
        pipeline.add(Aggregates.sort(Sorts.descending("startTs")));

        MongoCursor<BasicDBObject> endpointsCursor = SingleTypeInfoDao.instance.getMCollection().aggregate(pipeline, BasicDBObject.class).cursor();

        List<BasicDBObject> endpoints = new ArrayList<>();
        while(endpointsCursor.hasNext()) {
            endpoints.add(endpointsCursor.next());
        }

        return endpoints;
    }

    public static List<SingleTypeInfo> fetchHostSTI(int apiCollectionId, int skip) {
        Bson filterQ = SingleTypeInfoDao.filterForHostHeader(apiCollectionId, true);
        return SingleTypeInfoDao.instance.findAll(filterQ, skip,10_000, null);
    }
}
