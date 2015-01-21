#! /usr/bin/env python
'''
Created on Jan 21, 2015

@author: gaprice@lbl.gov
'''
from __future__ import print_function
import fileinput
import urllib2

JGI_URL = 'http://genome.jgi.doe.gov/ext-api/genome-admin/' +\
    'getPortalIdByParameter?parameterName=jgiProjectId&parameterValue='


def main():
    for line in fileinput.input():
        line = line.strip()
        url = JGI_URL + line
        try:
            projects = urllib2.urlopen(url).read()
        except urllib2.HTTPError as e:
            print(line + '\t' + '***ERROR***: ' + str(e))
        else:
            print(line + '\t' + projects)

if __name__ == '__main__':
    main()
