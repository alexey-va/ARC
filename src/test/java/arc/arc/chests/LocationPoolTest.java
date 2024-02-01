package arc.arc.chests;

import arc.arc.network.ServerLocation;
import arc.arc.treasurechests.locationpools.LocationPool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LocationPoolTest {
    @Test
    void testSerializer() throws JsonProcessingException {
        LocationPool locationPool = new LocationPool("test");
        locationPool.getLocations().add(new ServerLocation("a", "b", 1,2,3,4,5));

        ObjectMapper mapper = new ObjectMapper();
        System.out.println(mapper.writeValueAsString(locationPool));
    }
}