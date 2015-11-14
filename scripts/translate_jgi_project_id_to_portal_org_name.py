#! /usr/bin/env python
'''
Translates a set of JGI project IDs to their portal organism name. The
input is via python fileinput
(https://docs.python.org/2/library/fileinput.html).

@author: gaprice@lbl.gov
'''
from __future__ import print_function
import fileinput
import urllib2
import sys

# JGI_URL = 'http://genome.jgi.doe.gov/ext-api/genome-admin/' +\
#     'getPortalIdByParameter?parameterName=jgiProjectId&parameterValue='

JGI_URL = 'http://128.55.71.129/ext-api/genome-admin/' +\
    'getPortalIdByParameter?parameterName=jgiProjectId&parameterValue='


def main():
    for line in fileinput.input():
        line = line.strip()
        url = JGI_URL + line
        failed = True
        while (failed):
            try:
                projects = urllib2.urlopen(url).read()
            except urllib2.HTTPError as e:
                print(line + '\t' + '***ERROR***: ' + str(e))
                failed = False
            except urllib2.URLError as e:
                print('Error for ' + line + ': ' + str(e) + ', retrying',
                      file=sys.stderr)
            else:
                print(line + '\t' + projects)
                failed = False

if __name__ == '__main__':
    main()
