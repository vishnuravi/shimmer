package org.openmhealth.shim.fitbit;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.openmhealth.schema.pojos.Activity;
import org.openmhealth.schema.pojos.NumberOfSteps;
import org.openmhealth.schema.pojos.base.DurationUnitValue;
import org.openmhealth.schema.pojos.base.LengthUnitValue;
import org.openmhealth.schema.pojos.base.TimeFrame;
import org.openmhealth.shim.*;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FitbitShim extends OAuth1ShimBase {
    public static final String SHIM_KEY = "fitbit";

    private static final String DATA_URL = "https://api.fitbit.com";

    private static final String REQUEST_TOKEN_URL = "https://api.fitbit.com/oauth/request_token";

    private static final String AUTHORIZE_URL = "https://www.fitbit.com/oauth/authenticate";

    private static final String TOKEN_URL = "https://api.fitbit.com/oauth/access_token";

    public static final String FITBIT_CLIENT_ID = "7da3c2e5e74d4492ab6bb3286fc32c6b";

    public static final String FITBIT_CLIENT_SECRET = "455a383f80de45d6a4f9b09e841da1f4";

    @Override
    public List<String> getScopes() {
        return null; //noop!
    }

    @Override
    public String getShimKey() {
        return SHIM_KEY;
    }

    @Override
    public String getClientSecret() {
        return FITBIT_CLIENT_SECRET;
    }

    @Override
    public String getClientId() {
        return FITBIT_CLIENT_ID;
    }

    @Override
    public String getBaseRequestTokenUrl() {
        return REQUEST_TOKEN_URL;
    }

    @Override
    public String getBaseAuthorizeUrl() {
        return AUTHORIZE_URL;
    }

    @Override
    public String getBaseTokenUrl() {
        return TOKEN_URL;
    }

    protected HttpMethod getRequestTokenMethod() {
        return HttpMethod.POST;
    }

    protected HttpMethod getAccessTokenMethod() {
        return HttpMethod.POST;
    }

    private static DateTimeFormatter formatterMins =
        DateTimeFormat.forPattern("yyyy-MM-dd HH:mm");

    public enum FitbitDataType implements ShimDataType {
        /**
         * The activity endpoint from fitbit retrieves
         * both activity and number of steps standard schemas.
         */
        ACTIVITY(
            new JsonDeserializer<ShimDataResponse>() {
                @Override
                public ShimDataResponse deserialize(JsonParser jsonParser,
                                                    DeserializationContext deserializationContext)
                    throws IOException {
                    JsonNode responseNode = jsonParser.getCodec().readTree(jsonParser);
                    String rawJson = responseNode.toString();

                    System.out.println("The JSON ingested is: \n\n" + rawJson + "\n\n");

                    List<Activity> activities = new ArrayList<>();
                    List<NumberOfSteps> numberOfStepsList = new ArrayList<>();

                    JsonPath activityPath = JsonPath.compile("$.activities[*]");

                    List<Object> fitbitActivities = JsonPath.read(rawJson, activityPath.getPath());
                    if (CollectionUtils.isEmpty(fitbitActivities)) {
                        return ShimDataResponse.result(null);
                    }
                    ObjectMapper mapper = new ObjectMapper();
                    for (Object fva : fitbitActivities) {
                        JsonNode fitbitActivity = mapper.readTree(((JSONObject) fva).toJSONString());

                        Activity activity = new Activity();
                        activity.setActivityName(fitbitActivity.get("activityParentName").asText());

                        LengthUnitValue distance = new LengthUnitValue();
                        distance.setValue(new BigDecimal(fitbitActivity.get("distance").asText()));
                        distance.setUnit(LengthUnitValue.LengthUnit.mi);
                        activity.setDistance(distance);

                        TimeFrame timeFrame = new TimeFrame();
                        DurationUnitValue durationUnitValue = new DurationUnitValue();
                        durationUnitValue.setValue(new BigDecimal(fitbitActivity.get("duration").asText()));
                        durationUnitValue.setUnit(DurationUnitValue.DurationUnit.ms);

                        timeFrame.setDuration(durationUnitValue);

                        String dateString = fitbitActivity.get("startDate").asText()
                            + (fitbitActivity.get("startTime") != null ?
                            " " + fitbitActivity.get("startTime").asText() : "");

                        timeFrame.setStartTime(formatterMins.parseDateTime(dateString));
                        activity.setEffectiveTimeFrame(timeFrame);

                        NumberOfSteps numberOfSteps = new NumberOfSteps();
                        numberOfSteps.setValue(fitbitActivity.get("steps").asInt());
                        numberOfSteps.setEffectiveTimeFrame(timeFrame);

                        activities.add(activity);
                        numberOfStepsList.add(numberOfSteps);
                    }

                    Collection<Object> results = new ArrayList<>();
                    results.addAll(activities);
                    results.addAll(numberOfStepsList);

                    return ShimDataResponse.result(results);
                }
            }
        );

        private JsonDeserializer<ShimDataResponse> normalizer;

        FitbitDataType(JsonDeserializer<ShimDataResponse> normalizer) {
            this.normalizer = normalizer;
        }

        @Override
        public JsonDeserializer<ShimDataResponse> getNormalizer() {
            return normalizer;
        }
    }

    @Override
    public ShimDataResponse getData(ShimDataRequest shimDataRequest) throws ShimException {
        AccessParameters accessParameters = shimDataRequest.getAccessParameters();
        String accessToken = accessParameters.getAccessToken();
        String tokenSecret = accessParameters.getTokenSecret();

        FitbitDataType fitbitDataType;
        try {
            fitbitDataType = FitbitDataType.valueOf(shimDataRequest.getDataTypeKey().trim().toUpperCase());
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new ShimException("Null or Invalid data type parameter: "
                + shimDataRequest.getDataTypeKey()
                + " in shimDataRequest, cannot retrieve data.");
        }

        String endPointUrl = DATA_URL;

        switch (fitbitDataType) {
            default:
            case ACTIVITY:
                endPointUrl += "/1/user/-/activities/date/2014-07-13.json";
                break;
        }

        HttpRequestBase dataRequest =
            OAuth1Utils.getSignedRequest(
                endPointUrl, getClientId(), getClientSecret(), accessToken, tokenSecret, null);

        HttpResponse response;
        try {
            response = httpClient.execute(dataRequest);
            HttpEntity responseEntity = response.getEntity();

            // Fetch and decode the JSON data.
            ObjectMapper objectMapper = new ObjectMapper();
            if (shimDataRequest.getNormalize()) {
                SimpleModule module = new SimpleModule();
                module.addDeserializer(ShimDataResponse.class, fitbitDataType.getNormalizer());
                objectMapper.registerModule(module);
                return objectMapper.readValue(responseEntity.getContent(), ShimDataResponse.class);
            } else {
                return ShimDataResponse.result(objectMapper.readTree(responseEntity.getContent()));
            }
        } catch (IOException e) {
            throw new ShimException("Could not fetch data", e);
        }
    }
}