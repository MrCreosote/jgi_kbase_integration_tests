#! /usr/bin/env python
'''
Created on Dec 4, 2014

@author: gaprice@lbl.gov
'''

from __future__ import print_function
from collections import defaultdict
from urllib2 import urlopen
import json
import numpy

START_JOB = 238
STOP_JOB = 260

JENKINS_URL = 'https://jenkins.kbase.us'
TEST_SUITE_NAME = 'jgi_kbase_integration_test'


def collect_test_data():
    url_prefix = JENKINS_URL + '/job/' + TEST_SUITE_NAME + '/'
    res = defaultdict(lambda: defaultdict(list))
    testcount = defaultdict(int)
    timings = defaultdict(list)
    for job in xrange(START_JOB, STOP_JOB + 1):
        url = url_prefix + str(job) + '/api/json'
        j = json.loads(urlopen(url).read())
        if j['result'] == 'ABORTED':
            continue
        url = url_prefix + str(job) + '/testReport/api/json'
        j = json.loads(urlopen(url).read())
        for test in j['suites'][0]['cases']:
            name = test['name']
            error = test['errorDetails']
            duration = float(test['duration'])
            testcount[name] += 1
            if error:
                res[name][error].append(job)
            else:
                timings[name].append(duration)
    return testcount, res, timings


def print_test_summary_data(testcount, res, timings):
    total = 0
    fails = 0
    print('Test\tTotal\tFails\tMean (s)\tStddev (s)\tN')
    for test in testcount:
        test_fails = 0
        if test in res:
            for error in res[test]:
                test_fails += len(res[test][error])
        mean = numpy.mean(timings[test])
        stddev = numpy.std(timings[test], ddof=0)
        N = testcount[test] - test_fails
        print(test + '\t' + str(testcount[test]) + '\t' + str(test_fails) +
              '\t' + str(mean) + '\t' + str(stddev) + '\t' + str(N))
        total += testcount[test]
        fails += test_fails
    print('TTL\t' + str(total) + '\t' + str(fails))


def print_test_errors(res):
    print('Test\tError\tCount\tTest IDs')
    for test in res:
        for error in res[test]:
            print(test + '\t' + error.replace('\n', ' ') + '\t' +
                  str(len(res[test][error])) + '\t' +
                  ','.join(map(str, res[test][error])))


def main():
    testcount, res, timings = collect_test_data()
    print('Results for jobs from ' + str(START_JOB) + ' to ' + str(STOP_JOB))
    print_test_summary_data(testcount, res, timings)
    print()
    print_test_errors(res)

if __name__ == '__main__':
    main()
