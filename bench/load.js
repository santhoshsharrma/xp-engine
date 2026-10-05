import http from 'k6/http';
import { check } from 'k6';

export const options = {
    stages: [
        { duration: '30s', target: 50 },
        { duration: '60s', target: 50 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const userId = Math.floor(Math.random() * 1000) + 10000;
    const eventId = `load-${__VU}-${__ITER}-${Date.now()}`;

    const response = http.post(
        'http://localhost:8080/events',
        JSON.stringify({
            userId: userId,
            eventId: eventId,
            type: 'LESSON_COMPLETED',
        }),
        {
            headers: {
                'Content-Type': 'application/json',
            },
        }
    );

    check(response, {
        'status 200': (r) => r.status === 200,
    });
}